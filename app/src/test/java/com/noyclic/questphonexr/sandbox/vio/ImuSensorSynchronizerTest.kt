package com.noyclic.questphonexr.sandbox.vio

import org.junit.Assert.*
import org.junit.Test

class ImuSensorSynchronizerTest {
    @Test fun interpolatesAccelerationAtGyroscopeTimestamp() {
        val synchronizer = ImuSensorSynchronizer()
        assertTrue(synchronizer.addAccelerometer(990_000_000L, Vec3.ZERO).isEmpty())
        assertTrue(synchronizer.addGyroscope(1_000_000_000L, Vec3(0.1, 0.2, 0.3), Quaternion.IDENTITY).isEmpty())

        val paired = synchronizer.addAccelerometer(1_010_000_000L, Vec3(2.0, 4.0, 6.0))
        assertEquals(1, paired.size)
        assertEquals(1_000_000_000L, paired.single().timestampNanos)
        assertEquals(1.0, paired.single().accelerationIncludingGravity.x, 1e-9)
        assertEquals(2.0, paired.single().accelerationIncludingGravity.y, 1e-9)
        assertEquals(3.0, paired.single().accelerationIncludingGravity.z, 1e-9)
    }

    @Test fun exactTimestampPairDoesNotWaitForAnotherAccelerometerSample() {
        val synchronizer = ImuSensorSynchronizer()
        synchronizer.addAccelerometer(1_000_000_000L, Vec3(1.0, 2.0, 3.0))
        val paired = synchronizer.addGyroscope(1_000_000_000L, Vec3.ZERO, Quaternion.IDENTITY)
        assertEquals(1, paired.size)
        assertEquals(Vec3(1.0, 2.0, 3.0), paired.single().accelerationIncludingGravity)
    }

    @Test fun rejectsWideInterpolationBracketAndDoesNotExtrapolate() {
        val synchronizer = ImuSensorSynchronizer(maximumInterpolationGapNanos = 20_000_000L)
        synchronizer.addAccelerometer(900_000_000L, Vec3.ZERO)
        synchronizer.addGyroscope(1_000_000_000L, Vec3.ZERO, Quaternion.IDENTITY)
        assertTrue(synchronizer.addAccelerometer(1_050_000_000L, Vec3.ZERO).isEmpty())
        assertEquals(0, synchronizer.bufferedGyroscopeSamples())
    }

    @Test fun consumesEachGyroscopeSampleOnlyOnceAndBoundsBuffers() {
        val synchronizer = ImuSensorSynchronizer(maximumBufferedSamples = 4)
        synchronizer.addAccelerometer(1_000_000_000L, Vec3.ZERO)
        assertEquals(1, synchronizer.addGyroscope(1_000_000_000L, Vec3.ZERO, Quaternion.IDENTITY).size)
        assertTrue(synchronizer.addGyroscope(1_005_000_000L, Vec3.ZERO, Quaternion.IDENTITY).isEmpty())
        assertTrue(synchronizer.bufferedGyroscopeSamples() <= 4)
        assertTrue(synchronizer.bufferedAccelerometerSamples() <= 4)
    }

    @Test fun resetClearsBothTimestampQueues() {
        val synchronizer = ImuSensorSynchronizer()
        synchronizer.addAccelerometer(1_000_000_000L, Vec3.ZERO)
        synchronizer.addGyroscope(1_010_000_000L, Vec3.ZERO, Quaternion.IDENTITY)
        synchronizer.reset()
        assertEquals(0, synchronizer.bufferedGyroscopeSamples())
        assertEquals(0, synchronizer.bufferedAccelerometerSamples())
        assertTrue(synchronizer.addGyroscope(1_000_000_000L, Vec3.ZERO, Quaternion.IDENTITY).isEmpty())
    }
}
