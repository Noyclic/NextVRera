package com.noyclic.questphonexr.sandbox.vio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class VioFusionConfig(
    val cameraFocalLengthPixels: Double? = null,
    val maximumVisualGapSeconds: Double = 0.25,
    val orientationVisualWeight: Double = 0.08,
    val minimumVisualConfidence: Double = 0.18,
    val maximumRelativeStep: Double = 0.08,
    val stationaryAccelerationThreshold: Double = 0.18
) {
    init {
        require(maximumVisualGapSeconds > 0.0)
        require(orientationVisualWeight in 0.0..1.0)
        require(minimumVisualConfidence in 0.0..1.0)
        require(maximumRelativeStep > 0.0)
        require(stationaryAccelerationThreshold > 0.0)
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
    val lastResetReason: String
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
    private var imuSamples = 0
    private var visualFrames = 0
    private var acceptedVisual = 0
    private var rejectedVisual = 0
    private var lastConfidence = 0.0
    private var orientationSource = "not initialized"
    private var resetReason = "startup"
    private var lastVisualDelta = Vec2(0.0, 0.0)

    @Synchronized
    fun reset(reason: String = "manual reset") {
        orientation = Quaternion.IDENTITY
        position = Vec3.ZERO
        lastImuTimestamp = 0L
        lastVisualTimestamp = 0L
        imuSamples = 0
        visualFrames = 0
        acceptedVisual = 0
        rejectedVisual = 0
        lastConfidence = 0.0
        orientationSource = "not initialized"
        lastVisualDelta = Vec2(0.0, 0.0)
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
        imuSamples++
        return true
    }

    @Synchronized
    fun onAccelerometer(
        timestampNanos: Long,
        accelerationIncludingGravity: Vec3,
        bodyToWorld: Quaternion = orientation
    ): Boolean {
        if (timestampNanos <= 0L || !accelerationIncludingGravity.isFinite()) return false
        val accepted = imu.addWithGravityRemoval(
            timestampNanos,
            Vec3.ZERO,
            accelerationIncludingGravity,
            bodyToWorld
        )
        if (accepted) imuSamples++
        return accepted
    }

    @Synchronized
    fun onRotationVector(timestampNanos: Long, values: FloatArray): Boolean {
        if (timestampNanos <= 0L) return false
        val measured = Quaternion.fromAndroidRotationVector(values) ?: return false
        orientation = orientation.slerp(measured, 0.72)
        orientationSource = "rotation-vector + gyro"
        return true
    }

    @Synchronized
    fun onVisualFrame(frame: GrayFrame): VisualMotionEstimate {
        visualFrames++
        val estimate = features.process(frame)
        lastVisualTimestamp = frame.timestampNanos
        lastConfidence = estimate.confidence
        if (!estimate.valid || estimate.confidence < config.minimumVisualConfidence) {
            rejectedVisual++
            lastVisualDelta = Vec2(0.0, 0.0)
            return estimate
        }
        val previousTimestamp = lastVisualTimestamp
        val gapSeconds = if (previousTimestamp == 0L) 0.0
            else (frame.timestampNanos - previousTimestamp) * 1e-9
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
        if (focal != null) {
            val pixelDx = estimate.displacementPixels.x
            val pixelDy = estimate.displacementPixels.y
            val angularX = orientation.x * 2.0
            val angularY = orientation.y * 2.0
            dx -= angularY * focal / max(1.0, focal)
            dy += angularX * focal / max(1.0, focal)
            if (!pixelDx.isFinite() || !pixelDy.isFinite()) {
                rejectedVisual++
                return estimate.copy(valid = false, reason = "non-finite visual flow")
            }
        }
        val boundedX = dx.coerceIn(-config.maximumRelativeStep, config.maximumRelativeStep)
        val boundedY = dy.coerceIn(-config.maximumRelativeStep, config.maximumRelativeStep)
        val weight = estimate.confidence.coerceIn(0.0, 1.0)
        position += Vec3(-boundedX * weight, boundedY * weight, 0.0)
        lastVisualDelta = Vec2(boundedX, boundedY)
        acceptedVisual++
        return estimate
    }

    @Synchronized
    fun pose(timestampNanos: Long): VioPose {
        val visualFresh = lastVisualTimestamp > 0L &&
            timestampNanos >= lastVisualTimestamp &&
            (timestampNanos - lastVisualTimestamp) * 1e-9 <= config.maximumVisualGapSeconds
        val quality = if (visualFresh) {
            min(1.0, 0.35 + lastConfidence * 0.65)
        } else if (orientationSource != "not initialized") 0.2 else 0.0
        val imuState = imu.snapshot()
        val reason = when {
            visualFresh && imuState.valid -> "visual-inertial relative pose; metric scale unavailable"
            visualFresh -> "visual relative pose; IMU preintegration incomplete"
            orientationSource != "not initialized" -> "orientation only; translation not observable"
            else -> "waiting for camera and IMU"
        }
        return VioPose(
            timestampNanos = timestampNanos,
            orientation = orientation.normalized(),
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
        lastResetReason = resetReason
    )

    @Synchronized
    fun latestVisualDelta() = lastVisualDelta
}
