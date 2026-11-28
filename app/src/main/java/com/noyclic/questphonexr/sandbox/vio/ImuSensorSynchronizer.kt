package com.noyclic.questphonexr.sandbox.vio

/**
 * Pairs gyro samples with linearly interpolated accelerometer measurements at the gyro timestamp.
 *
 * Samples are consumed monotonically: an accelerometer value is never silently reused for a
 * later gyro event. Pairing waits for a future accelerometer bracket and rejects stale or
 * excessively wide brackets instead of extrapolating.
 */
data class PairedImuSample(
    val timestampNanos: Long,
    val angularVelocity: Vec3,
    val accelerationIncludingGravity: Vec3,
    val bodyToWorld: Quaternion
)

class ImuSensorSynchronizer(
    private val maximumInterpolationGapNanos: Long = 40_000_000L,
    private val maximumBufferedSamples: Int = 128
) {
    private data class GyroSample(
        val timestampNanos: Long,
        val angularVelocity: Vec3,
        val bodyToWorld: Quaternion
    )
    private data class AccelSample(val timestampNanos: Long, val value: Vec3)

    private val gyroscope = ArrayList<GyroSample>()
    private val accelerometer = ArrayList<AccelSample>()
    private var lastGyroTimestamp = 0L
    private var lastAccelTimestamp = 0L

    init {
        require(maximumInterpolationGapNanos > 0L)
        require(maximumBufferedSamples >= 2)
    }

    @Synchronized
    fun reset() {
        gyroscope.clear()
        accelerometer.clear()
        lastGyroTimestamp = 0L
        lastAccelTimestamp = 0L
    }

    @Synchronized
    fun addGyroscope(timestampNanos: Long, angularVelocity: Vec3, bodyToWorld: Quaternion): List<PairedImuSample> {
        if (timestampNanos <= lastGyroTimestamp || timestampNanos <= 0L || !angularVelocity.isFinite()) return emptyList()
        lastGyroTimestamp = timestampNanos
        gyroscope += GyroSample(timestampNanos, angularVelocity, bodyToWorld.normalized())
        trimBuffers()
        return drainPairs()
    }

    @Synchronized
    fun addAccelerometer(timestampNanos: Long, accelerationIncludingGravity: Vec3): List<PairedImuSample> {
        if (timestampNanos <= lastAccelTimestamp || timestampNanos <= 0L || !accelerationIncludingGravity.isFinite()) return emptyList()
        lastAccelTimestamp = timestampNanos
        accelerometer += AccelSample(timestampNanos, accelerationIncludingGravity)
        trimBuffers()
        return drainPairs()
    }

    private fun drainPairs(): List<PairedImuSample> {
        if (gyroscope.isEmpty() || accelerometer.isEmpty()) return emptyList()
        val output = ArrayList<PairedImuSample>()
        val iterator = gyroscope.iterator()
        while (iterator.hasNext()) {
            val gyro = iterator.next()
            val upperIndex = accelerometer.indexOfFirst { it.timestampNanos >= gyro.timestampNanos }
            if (upperIndex < 0) {
                if (accelerometer.last().timestampNanos - gyro.timestampNanos > maximumInterpolationGapNanos) {
                    iterator.remove()
                }
                continue
            }
            val upper = accelerometer[upperIndex]
            val acceleration = if (upper.timestampNanos == gyro.timestampNanos) {
                upper.value
            } else {
                if (upperIndex == 0) {
                    iterator.remove()
                    continue
                }
                val lower = accelerometer[upperIndex - 1]
                val span = upper.timestampNanos - lower.timestampNanos
                if (span <= 0L || span > maximumInterpolationGapNanos ||
                    gyro.timestampNanos - lower.timestampNanos > maximumInterpolationGapNanos
                ) {
                    iterator.remove()
                    continue
                }
                val fraction = (gyro.timestampNanos - lower.timestampNanos).toDouble() / span.toDouble()
                lower.value + (upper.value - lower.value) * fraction
            }
            output += PairedImuSample(
                gyro.timestampNanos,
                gyro.angularVelocity,
                acceleration,
                gyro.bodyToWorld
            )
            iterator.remove()
        }
        // Keep only the last sample before the earliest pending gyro plus all newer samples.
        val earliestPending = gyroscope.firstOrNull()?.timestampNanos
        if (earliestPending != null) {
            val keepFrom = (accelerometer.indexOfLast { it.timestampNanos < earliestPending }).coerceAtLeast(0)
            if (keepFrom > 0) accelerometer.subList(0, keepFrom).clear()
        } else if (accelerometer.size > 2) {
            accelerometer.subList(0, accelerometer.size - 2).clear()
        }
        return output
    }

    private fun trimBuffers() {
        if (gyroscope.size > maximumBufferedSamples) {
            gyroscope.subList(0, gyroscope.size - maximumBufferedSamples).clear()
        }
        if (accelerometer.size > maximumBufferedSamples) {
            accelerometer.subList(0, accelerometer.size - maximumBufferedSamples).clear()
        }
    }

    @Synchronized
    fun bufferedGyroscopeSamples() = gyroscope.size

    @Synchronized
    fun bufferedAccelerometerSamples() = accelerometer.size
}
