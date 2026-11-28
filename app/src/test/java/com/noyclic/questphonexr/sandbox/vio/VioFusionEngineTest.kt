package com.noyclic.questphonexr.sandbox.vio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class VioFusionEngineTest {
    @Test fun rotationVectorProducesOrientationOnlyPoseWithoutClaimingTranslation() {
        val engine = VioFusionEngine()
        val half = (PI / 4.0).toFloat()
        assertTrue(engine.onRotationVector(1_000_000_000L, floatArrayOf(0f, half, 0f, kotlin.math.cos(half))))
        val pose = engine.pose(1_010_000_000L)
        assertTrue(pose.isValid)
        assertEquals(TranslationScale.UNKNOWN, pose.translationScale)
        assertEquals(0.0, pose.position.length(), 0.0)
        assertTrue(pose.diagnostic.contains("orientation only"))
    }

    @Test fun gyroUpdatesAreMonotonicAndBounded() {
        val engine = VioFusionEngine()
        assertTrue(engine.onGyroscope(1_000_000_000L, Vec3.ZERO))
        assertTrue(engine.onGyroscope(1_010_000_000L, Vec3(0.0, PI, 0.0)))
        assertFalse(engine.onGyroscope(1_005_000_000L, Vec3.ZERO))
        assertTrue(engine.pose(1_020_000_000L).orientation.angularDistance(Quaternion.IDENTITY) > 0.0)
    }

    @Test fun visualMotionNeverGetsMetricScaleByDefault() {
        val engine = VioFusionEngine()
        engine.onVisualFrame(texture(1_000_000_000L, 0))
        val motion = engine.onVisualFrame(texture(1_033_000_000L, 2))
        val pose = engine.pose(1_040_000_000L)
        assertTrue(motion.valid)
        assertTrue(pose.isValid)
        assertEquals(TranslationScale.RELATIVE_ONLY, pose.translationScale)
        assertFalse(pose.diagnostic.contains("metres"))
        assertTrue(engine.diagnostics().acceptedVisualUpdates > 0)
    }

    @Test fun resetClearsVisualAndImuHistory() {
        val engine = VioFusionEngine()
        engine.onRotationVector(1_000_000_000L, floatArrayOf(0f, 0f, 0f, 1f))
        engine.onVisualFrame(texture(1_000_000_000L, 0))
        engine.reset("test")
        val diagnostics = engine.diagnostics()
        assertEquals(0, diagnostics.visualFrames)
        assertEquals(0, diagnostics.imuSamples)
        assertEquals("test", diagnostics.lastResetReason)
        assertFalse(engine.pose(2_000_000_000L).isValid)
    }

    private fun texture(timestamp: Long, shift: Int): GrayFrame {
        val width = 160
        val height = 120
        val data = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shift
            val sy = y
            val value = if (sx in 0 until width) {
                val mixed = (sx * 73 + sy * 151 + sx * sy * 19) xor (sx * 31 + sy * 47)
                (mixed ushr 3) and 0xff
            } else 0
            data[y * width + x] = value.toByte()
        }
        return GrayFrame(timestamp, width, height, data)
    }
}
