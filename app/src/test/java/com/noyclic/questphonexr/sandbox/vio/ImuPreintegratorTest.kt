package com.noyclic.questphonexr.sandbox.vio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ImuPreintegratorTest {
    @Test fun constantAngularVelocityIntegratesExpectedRotation() {
        val integrator = ImuPreintegrator()
        integrator.add(TimedImuSample(1_000_000_000L, Vec3(0.0, 0.0, PI), Vec3.ZERO))
        integrator.add(TimedImuSample(1_010_000_000L, Vec3(0.0, 0.0, PI), Vec3.ZERO))
        integrator.add(TimedImuSample(1_020_000_000L, Vec3(0.0, 0.0, PI), Vec3.ZERO))
        val result = integrator.snapshot()
        assertTrue(result.valid)
        assertEquals(0.02, result.integratedSeconds, 1e-8)
        assertEquals(PI * 0.02, result.deltaRotation.angularDistance(Quaternion.IDENTITY), 1e-6)
    }

    @Test fun constantAccelerationProducesQuadraticDisplacement() {
        val integrator = ImuPreintegrator()
        integrator.add(TimedImuSample(1_000_000_000L, Vec3.ZERO, Vec3(2.0, 0.0, 0.0)))
        integrator.add(TimedImuSample(1_010_000_000L, Vec3.ZERO, Vec3(2.0, 0.0, 0.0)))
        integrator.add(TimedImuSample(1_020_000_000L, Vec3.ZERO, Vec3(2.0, 0.0, 0.0)))
        val result = integrator.snapshot()
        assertTrue(result.valid)
        assertEquals(0.0004, result.deltaPosition.x, 1e-7)
        assertEquals(0.04, result.deltaVelocity.x, 1e-7)
    }

    @Test fun nonMonotonicAndHugeGapsAreRejected() {
        val integrator = ImuPreintegrator()
        assertTrue(integrator.add(TimedImuSample(1_000_000_000L, Vec3.ZERO, Vec3.ZERO)))
        assertFalse(integrator.add(TimedImuSample(999_000_000L, Vec3.ZERO, Vec3.ZERO)))
        assertFalse(integrator.add(TimedImuSample(1_300_000_000L, Vec3.ZERO, Vec3.ZERO)))
        val result = integrator.snapshot()
        assertEquals(1, result.rejectedSamples)
        assertEquals(1, result.gapCount)
        assertFalse(result.valid)
    }

    @Test fun biasEstimationRequiresStationarySamples() {
        val integrator = ImuPreintegrator()
        val stationary = (0 until 12).map {
            TimedImuSample(1_000_000_000L + it * 10_000_000L, Vec3(0.01, -0.02, 0.005), Vec3(0.0, 0.0, 9.80665))
        }
        val bias = integrator.estimateGyroBias(stationary)
        assertNotNull(bias)
        assertEquals(0.01, bias!!.gyroscope.x, 1e-9)
        val moving = stationary.mapIndexed { index, sample ->
            sample.copy(angularVelocity = if (index == 3) Vec3(1.0, 0.0, 0.0) else sample.angularVelocity)
        }
        assertNull(integrator.estimateGyroBias(moving))
    }

    @Test fun resetClearsAccumulatedStateButKeepsConfiguredBias() {
        val integrator = ImuPreintegrator()
        val bias = ImuBias(Vec3(0.01, 0.0, 0.0), Vec3.ZERO)
        integrator.setBias(bias)
        integrator.add(TimedImuSample(1_000_000_000L, Vec3.ZERO, Vec3.ZERO))
        integrator.reset()
        assertEquals(0, integrator.snapshot().sampleCount)
        assertEquals(bias, integrator.biasSnapshot())
    }
}
