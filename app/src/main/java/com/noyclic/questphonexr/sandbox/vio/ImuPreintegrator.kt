package com.noyclic.questphonexr.sandbox.vio

import kotlin.math.abs

data class TimedImuSample(
    val timestampNanos: Long,
    val angularVelocity: Vec3,
    val linearAcceleration: Vec3
) {
    fun isValid() = timestampNanos > 0L && angularVelocity.isFinite() && linearAcceleration.isFinite()
}

data class ImuBias(
    val gyroscope: Vec3 = Vec3.ZERO,
    val accelerometer: Vec3 = Vec3.ZERO
)

data class ImuIntegrationSnapshot(
    val startTimestampNanos: Long,
    val endTimestampNanos: Long,
    val deltaRotation: Quaternion,
    val deltaVelocity: Vec3,
    val deltaPosition: Vec3,
    val integratedSeconds: Double,
    val sampleCount: Int,
    val rejectedSamples: Int,
    val gapCount: Int,
    val valid: Boolean,
    val reason: String
)

/**
 * First-order midpoint preintegrator.
 *
 * Acceleration is expressed in the IMU/body frame. The caller must provide a gravity-compensated
 * signal or use the orientation-aware overload. This class never guesses gravity direction.
 * Large timestamp gaps are rejected instead of integrating a fictitious constant acceleration.
 */
class ImuPreintegrator(
    private val maximumStepSeconds: Double = 0.05,
    private val maximumGapSeconds: Double = 0.12,
    private val minimumStepSeconds: Double = 1e-5,
    private val maximumSamples: Int = 20_000
) {
    private var bias = ImuBias()
    private var previous: TimedImuSample? = null
    private var startTimestamp = 0L
    private var endTimestamp = 0L
    private var deltaRotation = Quaternion.IDENTITY
    private var deltaVelocity = Vec3.ZERO
    private var deltaPosition = Vec3.ZERO
    private var integratedSeconds = 0.0
    private var sampleCount = 0
    private var rejectedSamples = 0
    private var gapCount = 0
    private var invalidReason: String? = null

    init {
        require(maximumStepSeconds > 0.0)
        require(maximumGapSeconds >= maximumStepSeconds)
        require(minimumStepSeconds > 0.0)
        require(maximumSamples > 1)
    }

    @Synchronized
    fun setBias(value: ImuBias) {
        bias = value
    }

    @Synchronized
    fun reset(timestampNanos: Long = 0L) {
        previous = null
        startTimestamp = timestampNanos
        endTimestamp = timestampNanos
        deltaRotation = Quaternion.IDENTITY
        deltaVelocity = Vec3.ZERO
        deltaPosition = Vec3.ZERO
        integratedSeconds = 0.0
        sampleCount = 0
        rejectedSamples = 0
        gapCount = 0
        invalidReason = null
    }

    @Synchronized
    fun add(sample: TimedImuSample): Boolean {
        if (!sample.isValid()) {
            rejectedSamples++
            invalidReason = "non-finite or invalid IMU sample"
            return false
        }
        if (sampleCount >= maximumSamples) {
            rejectedSamples++
            invalidReason = "sample capacity exceeded"
            return false
        }
        val old = previous
        if (old == null) {
            if (startTimestamp == 0L) startTimestamp = sample.timestampNanos
            previous = sample
            endTimestamp = sample.timestampNanos
            sampleCount = 1
            return true
        }
        val deltaNanos = sample.timestampNanos - old.timestampNanos
        if (deltaNanos <= 0L) {
            rejectedSamples++
            invalidReason = "non-monotonic IMU timestamp"
            return false
        }
        val dt = deltaNanos * 1e-9
        if (dt < minimumStepSeconds) {
            rejectedSamples++
            invalidReason = "IMU interval below minimum"
            return false
        }
        if (dt > maximumGapSeconds) {
            gapCount++
            previous = sample
            endTimestamp = sample.timestampNanos
            invalidReason = "IMU gap exceeded integration window"
            return false
        }

        val omega0 = old.angularVelocity - bias.gyroscope
        val omega1 = sample.angularVelocity - bias.gyroscope
        val omega = (omega0 + omega1) * 0.5
        val acceleration0 = old.linearAcceleration - bias.accelerometer
        val acceleration1 = sample.linearAcceleration - bias.accelerometer
        val bodyAcceleration = (acceleration0 + acceleration1) * 0.5
        val midpointRotation = deltaRotation * Quaternion.fromAngularVelocity(omega, dt * 0.5)
        val referenceAcceleration = midpointRotation.rotate(bodyAcceleration)

        // Midpoint integration in the preintegration reference frame.
        deltaPosition += deltaVelocity * dt + referenceAcceleration * (0.5 * dt * dt)
        deltaVelocity += referenceAcceleration * dt
        deltaRotation = (deltaRotation * Quaternion.fromAngularVelocity(omega, dt)).normalized()
        integratedSeconds += dt
        endTimestamp = sample.timestampNanos
        sampleCount++
        previous = sample
        invalidReason = null
        return true
    }

    /**
     * Adds an accelerometer sample after removing gravity in the supplied world orientation.
     * Android accelerometer includes gravity; this overload makes that assumption explicit.
     */
    @Synchronized
    fun addWithGravityRemoval(
        timestampNanos: Long,
        angularVelocity: Vec3,
        accelerationIncludingGravity: Vec3,
        bodyToWorld: Quaternion,
        gravityWorld: Vec3 = Vec3(0.0, 9.80665, 0.0)
    ): Boolean {
        val gravityBody = bodyToWorld.normalized().conjugate().rotate(gravityWorld)
        val linear = accelerationIncludingGravity - gravityBody
        return add(TimedImuSample(timestampNanos, angularVelocity, linear))
    }

    @Synchronized
    fun snapshot(): ImuIntegrationSnapshot {
        val valid = sampleCount >= 2 && integratedSeconds > 0.0 &&
            invalidReason == null && deltaVelocity.isFinite() && deltaPosition.isFinite()
        return ImuIntegrationSnapshot(
            startTimestampNanos = startTimestamp,
            endTimestampNanos = endTimestamp,
            deltaRotation = deltaRotation.normalized(),
            deltaVelocity = deltaVelocity,
            deltaPosition = deltaPosition,
            integratedSeconds = integratedSeconds,
            sampleCount = sampleCount,
            rejectedSamples = rejectedSamples,
            gapCount = gapCount,
            valid = valid,
            reason = invalidReason ?: if (valid) "ok" else "insufficient IMU samples"
        )
    }

    @Synchronized
    fun integrate(samples: Iterable<TimedImuSample>): ImuIntegrationSnapshot {
        reset()
        samples.forEach(::add)
        return snapshot()
    }

    @Synchronized
    fun estimateGyroBias(stationarySamples: List<TimedImuSample>, maximumMotionRadiansPerSecond: Double = 0.08): ImuBias? {
        if (stationarySamples.size < 8) return null
        if (stationarySamples.any { !it.isValid() }) return null
        val rates = stationarySamples.map { it.angularVelocity.length() }
        if (rates.any { !it.isFinite() || it > maximumMotionRadiansPerSecond }) return null
        val gyro = Vec3(
            stationarySamples.map { it.angularVelocity.x }.average(),
            stationarySamples.map { it.angularVelocity.y }.average(),
            stationarySamples.map { it.angularVelocity.z }.average()
        )
        val accel = Vec3(
            stationarySamples.map { it.linearAcceleration.x }.average(),
            stationarySamples.map { it.linearAcceleration.y }.average(),
            stationarySamples.map { it.linearAcceleration.z }.average()
        )
        return ImuBias(gyro, accel).also { bias = it }
    }

    @Synchronized
    fun clearBias() {
        bias = ImuBias()
    }

    @Synchronized
    fun biasSnapshot() = bias

    companion object {
        fun secondsBetween(firstNanos: Long, secondNanos: Long): Double {
            if (firstNanos <= 0L || secondNanos <= firstNanos) return 0.0
            return (secondNanos - firstNanos) * 1e-9
        }
    }
}
