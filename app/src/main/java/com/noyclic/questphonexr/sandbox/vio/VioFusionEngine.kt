package com.noyclic.questphonexr.sandbox.vio

import kotlin.math.max
import kotlin.math.min

data class VioFusionConfig(
    val cameraFocalLengthPixels: Double? = null,
    val maximumVisualGapSeconds: Double = 0.25,
    val minimumVisualConfidence: Double = 0.18,
    val maximumRelativeStep: Double = 0.08,
) {
    init {
        require(maximumVisualGapSeconds > 0.0)
        require(minimumVisualConfidence in 0.0..1.0)
        require(maximumRelativeStep > 0.0)
        require(cameraFocalLengthPixels == null || cameraFocalLengthPixels > 0.0)
    }
}

data class VioDiagnostics(
    val imuSamples: Int,
    val visualFrames: Int,
    val acceptedVisualUpdates: Int,
    val rejectedVisualUpdates: Int,
    val lastVisualConfidence: Double,
    val orientationSource: String,
    val scaleStatus: String,
    val lastResetReason: String,
    val cameraImuClockComparable: Boolean
)

/**
 * Conservative camera + IMU fusion coordinator.
 *
 * Rotation-vector updates provide absolute-yaw-limited orientation when available; gyro
 * preintegration fills the intervals. Sparse image flow contributes a small correction and a
 * relative image-plane displacement only. Without depth, stereo, known scene geometry, or an
 * external reference, translation scale is fundamentally ambiguous and is never called metres.
 */
class VioFusionEngine(
    private val config: VioFusionConfig = VioFusionConfig(),
    private val imu: ImuPreintegrator = ImuPreintegrator(),
    private val features: SparseFeatureTracker = SparseFeatureTracker()
) {
    private var orientation = Quaternion.IDENTITY
    private var position = Vec3.ZERO
    private var lastImuTimestamp = 0L
    private var lastVisualTimestamp = 0L
    private var lastAcceptedVisualTimestamp = 0L
    private var imuSamples = 0
    private var visualFrames = 0
    private var acceptedVisual = 0
    private var rejectedVisual = 0
    private var lastConfidence = 0.0
    private var orientationSource = "not initialized"
    private var resetReason = "startup"
    private var lastVisualDelta = Vec2(0.0, 0.0)
    private var lastVisualOrientation = Quaternion.IDENTITY
    private var lastVisualOrientationSynchronized = false
    private var cameraTimestampComparable = false
    private data class OrientationSample(val timestampNanos: Long, val orientation: Quaternion)
    private val orientationHistory = ArrayList<OrientationSample>()
    private val imuSynchronizer = ImuSensorSynchronizer()

    @Synchronized
    fun reset(reason: String = "manual reset") {
        orientation = Quaternion.IDENTITY
        position = Vec3.ZERO
        lastImuTimestamp = 0L
        lastVisualTimestamp = 0L
        lastAcceptedVisualTimestamp = 0L
        imuSamples = 0
        visualFrames = 0
        acceptedVisual = 0
        rejectedVisual = 0
        lastConfidence = 0.0
        orientationSource = "not initialized"
        lastVisualDelta = Vec2(0.0, 0.0)
        lastVisualOrientation = Quaternion.IDENTITY
        lastVisualOrientationSynchronized = false
        orientationHistory.clear()
        imuSynchronizer.reset()
        resetReason = reason
        imu.reset()
        features.reset()
    }

    @Synchronized
    fun onGyroscope(timestampNanos: Long, radiansPerSecond: Vec3): Boolean {
        if (timestampNanos <= 0L || !radiansPerSecond.isFinite()) return false
        if (lastImuTimestamp != 0L && timestampNanos <= lastImuTimestamp) return false
        if (lastImuTimestamp != 0L) {
            val dt = (timestampNanos - lastImuTimestamp) * 1e-9
            if (dt > 0.0 && dt <= 0.12) {
                orientation = (orientation * Quaternion.fromAngularVelocity(radiansPerSecond, dt)).normalized()
                orientationSource = "gyro-integrated"
            } else if (dt > 0.12) {
                resetReason = "gyro timestamp gap"
            }
        }
        lastImuTimestamp = timestampNanos
        recordOrientation(timestampNanos, orientation)
        val pairedSamples = imuSynchronizer.addGyroscope(timestampNanos, radiansPerSecond, orientation)
        integratePairedSamples(pairedSamples)
        return true
    }

    @Synchronized
    fun onAccelerometer(
        timestampNanos: Long,
        accelerationIncludingGravity: Vec3
    ): Boolean {
        if (timestampNanos <= 0L || !accelerationIncludingGravity.isFinite()) return false
        val pairedSamples = imuSynchronizer.addAccelerometer(timestampNanos, accelerationIncludingGravity)
        integratePairedSamples(pairedSamples)
        return pairedSamples.isNotEmpty()
    }

    private fun integratePairedSamples(samples: List<PairedImuSample>) {
        samples.forEach { paired ->
            val gravityBody = paired.bodyToWorld.normalized().conjugate()
                .rotate(Vec3(0.0, 9.80665, 0.0))
            val linearAcceleration = paired.accelerationIncludingGravity - gravityBody
            if (imu.add(TimedImuSample(paired.timestampNanos, paired.angularVelocity, linearAcceleration))) {
                imuSamples++
            }
        }
    }

    @Synchronized
    fun onRotationVector(timestampNanos: Long, values: FloatArray): Boolean {
        if (timestampNanos <= 0L) return false
        val measured = Quaternion.fromAndroidRotationVector(values) ?: return false
        orientation = orientation.slerp(measured, 0.72)
        orientationSource = "rotation-vector + gyro"
        recordOrientation(timestampNanos, orientation)
        return true
    }

    @Synchronized
    fun onVisualFrame(frame: GrayFrame): VisualMotionEstimate {
        visualFrames++
        val previousVisualTimestamp = lastVisualTimestamp
        val previousOrientation = lastVisualOrientation
        val previousOrientationSynchronized = lastVisualOrientationSynchronized
        val frameOrientation = if (cameraTimestampComparable) orientationAt(frame.timestampNanos) else null
        val estimate = features.process(frame)
        lastVisualTimestamp = frame.timestampNanos
        lastVisualOrientation = frameOrientation ?: orientation
        lastVisualOrientationSynchronized = frameOrientation != null
        lastConfidence = estimate.confidence
        if (!estimate.valid || estimate.confidence < config.minimumVisualConfidence) {
            rejectedVisual++
            lastVisualDelta = Vec2(0.0, 0.0)
            return estimate
        }
        val gapSeconds = if (previousVisualTimestamp == 0L) 0.0
            else (frame.timestampNanos - previousVisualTimestamp) * 1e-9
        if (gapSeconds > config.maximumVisualGapSeconds) {
            rejectedVisual++
            resetReason = "visual frame gap"
            return estimate.copy(valid = false, reason = "visual frame gap; waiting for reacquisition")
        }

        // If intrinsics are known, remove first-order rotational image flow. Residual still has
        // unknown depth and is intentionally represented only in relative image-plane units.
        var dx = estimate.displacementNormalized.x
        var dy = estimate.displacementNormalized.y
        val focal = config.cameraFocalLengthPixels
        if (focal != null && previousVisualTimestamp > 0L &&
            previousOrientationSynchronized && frameOrientation != null
        ) {
            val delta = (previousOrientation.conjugate() * frameOrientation).normalized()
            val yawRadians = 2.0 * delta.y
            val pitchRadians = 2.0 * delta.x
            val width = max(1, estimate.imageWidth).toDouble()
            val height = max(1, estimate.imageHeight).toDouble()
            dx -= focal * yawRadians / width
            dy += focal * pitchRadians / height
        }
        val boundedX = dx.coerceIn(-config.maximumRelativeStep, config.maximumRelativeStep)
        val boundedY = dy.coerceIn(-config.maximumRelativeStep, config.maximumRelativeStep)
        val weight = estimate.confidence.coerceIn(0.0, 1.0)
        position += Vec3(-boundedX * weight, boundedY * weight, 0.0)
        lastVisualDelta = Vec2(boundedX, boundedY)
        lastAcceptedVisualTimestamp = frame.timestampNanos
        acceptedVisual++
        return estimate
    }

    @Synchronized
    fun pose(timestampNanos: Long): VioPose {
        val visualFresh = lastAcceptedVisualTimestamp > 0L &&
            timestampNanos >= lastAcceptedVisualTimestamp &&
            (timestampNanos - lastAcceptedVisualTimestamp) * 1e-9 <= config.maximumVisualGapSeconds
        val quality = if (visualFresh) {
            min(1.0, 0.35 + lastConfidence * 0.65)
        } else if (orientationSource != "not initialized") 0.2 else 0.0
        val imuState = imu.snapshot()
        val timestampOrientation = if (cameraTimestampComparable) orientationAt(timestampNanos) else null
        val reason = when {
            visualFresh && imuState.valid && timestampOrientation != null ->
                "visual-inertial relative pose; metric scale unavailable"
            visualFresh && imuState.valid && !cameraTimestampComparable ->
                "visual relative pose; camera/IMU clock domains not verified comparable"
            visualFresh && imuState.valid ->
                "visual relative pose; no IMU orientation sample brackets this camera timestamp"
            visualFresh -> "visual relative pose; IMU preintegration incomplete"
            orientationSource != "not initialized" -> "orientation only; translation not observable"
            else -> "waiting for camera and IMU"
        }
        return VioPose(
            timestampNanos = timestampNanos,
            orientation = (timestampOrientation ?: orientation).normalized(),
            position = position,
            translationScale = if (position == Vec3.ZERO) TranslationScale.UNKNOWN
                else TranslationScale.RELATIVE_ONLY,
            trackingQuality = quality,
            isValid = orientationSource != "not initialized" || visualFresh,
            diagnostic = reason
        ).sanitized()
    }

    @Synchronized
    fun diagnostics() = VioDiagnostics(
        imuSamples = imuSamples,
        visualFrames = visualFrames,
        acceptedVisualUpdates = acceptedVisual,
        rejectedVisualUpdates = rejectedVisual,
        lastVisualConfidence = lastConfidence,
        orientationSource = orientationSource,
        scaleStatus = "UNKNOWN: no metric depth or external scale",
        lastResetReason = resetReason,
        cameraImuClockComparable = cameraTimestampComparable
    )

    @Synchronized
    fun setCameraTimestampComparable(comparable: Boolean) {
        cameraTimestampComparable = comparable
        lastVisualOrientationSynchronized = false
    }

    private fun recordOrientation(timestampNanos: Long, value: Quaternion) {
        val sample = OrientationSample(timestampNanos, value.normalized())
        val existingIndex = orientationHistory.indexOfFirst { it.timestampNanos == timestampNanos }
        if (existingIndex >= 0) {
            orientationHistory[existingIndex] = sample
        } else {
            val insertAt = orientationHistory.indexOfFirst { it.timestampNanos > timestampNanos }
            if (insertAt < 0) orientationHistory.add(sample) else orientationHistory.add(insertAt, sample)
        }
        if (orientationHistory.size > 256) orientationHistory.removeAt(0)
    }

    private fun orientationAt(timestampNanos: Long): Quaternion? {
        if (timestampNanos <= 0L || orientationHistory.isEmpty()) return null
        val upperIndex = orientationHistory.indexOfFirst { it.timestampNanos >= timestampNanos }
        if (upperIndex < 0) return null
        val upper = orientationHistory[upperIndex]
        if (upper.timestampNanos == timestampNanos) return upper.orientation
        if (upperIndex == 0) return null
        val lower = orientationHistory[upperIndex - 1]
        val span = upper.timestampNanos - lower.timestampNanos
        if (span <= 0L || span > 40_000_000L) return null
        val fraction = (timestampNanos - lower.timestampNanos).toDouble() / span.toDouble()
        return lower.orientation.slerp(upper.orientation, fraction)
    }

    @Synchronized
    fun latestVisualDelta() = lastVisualDelta
}
