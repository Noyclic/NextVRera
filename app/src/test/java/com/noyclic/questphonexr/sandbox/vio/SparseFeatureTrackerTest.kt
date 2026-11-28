package com.noyclic.questphonexr.sandbox.vio

import org.junit.Assert.*
import org.junit.Test

class SparseFeatureTrackerTest {
    @Test fun detectsFeaturesAcrossImageGrid() {
        val tracker = SparseFeatureTracker()
        val frame = texturedFrame(1_000_000_000L, 160, 120, 0, 0)
        val features = tracker.detect(frame)
        assertTrue("expected a useful sparse feature set", features.size >= 8)
        assertTrue(features.size <= 180)
        assertTrue(features.map { it.x / 20 }.distinct().size > 2)
    }

    @Test fun estimatesKnownSmallImageTranslation() {
        val tracker = SparseFeatureTracker(
            FeatureTrackerConfig(
                maximumFeatures = 120,
                gridColumns = 6,
                gridRows = 4,
                cornerThreshold = 15,
                minimumDistancePixels = 5,
                maximumSearchRadius = 12,
                minimumMatches = 5,
                maximumHammingDistance = 30,
                ratioThreshold = 0.95
            )
        )
        tracker.process(texturedFrame(1_000_000_000L, 160, 120, 0, 0))
        val estimate = tracker.process(texturedFrame(1_033_000_000L, 160, 120, 2, 0))
        assertTrue("estimate rejected: ${estimate.reason}; matches=${estimate.matches}", estimate.valid)
        assertTrue(estimate.inliers >= 5)
        assertEquals(2.0, estimate.displacementPixels.x, 1.0)
        assertEquals(0.0, estimate.displacementPixels.y, 1.0)
    }

    @Test fun firstFrameAndDimensionChangesAreExplicitlyInvalid() {
        val tracker = SparseFeatureTracker()
        val first = tracker.process(texturedFrame(1_000_000_000L, 160, 120, 0, 0))
        assertFalse(first.valid)
        val changed = tracker.process(texturedFrame(1_033_000_000L, 128, 96, 0, 0))
        assertFalse(changed.valid)
        assertTrue(changed.reason.contains("dimensions"))
    }

    @Test fun timestampRegressionDoesNotProduceMotion() {
        val tracker = SparseFeatureTracker()
        tracker.process(texturedFrame(2_000_000_000L, 160, 120, 0, 0))
        val result = tracker.process(texturedFrame(1_900_000_000L, 160, 120, 1, 0))
        assertFalse(result.valid)
        assertTrue(result.reason.contains("timestamp"))
    }

    @Test fun blankFramesNeverInventMotion() {
        val tracker = SparseFeatureTracker()
        val blank = GrayFrame(1_000_000_000L, 96, 72, ByteArray(96 * 72))
        tracker.process(blank)
        val result = tracker.process(blank.copy(timestampNanos = 1_033_000_000L))
        assertFalse(result.valid)
        assertEquals(0, result.matches)
    }

    private fun texturedFrame(
        timestamp: Long,
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int
    ): GrayFrame {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = x - shiftX
                val sourceY = y - shiftY
                val value = if (sourceX in 0 until width && sourceY in 0 until height) {
                    // Deterministic high-frequency texture with stable translated patches.
                    val mixed = (sourceX * 73 + sourceY * 151 + sourceX * sourceY * 19) xor
                        (sourceX * 31 + sourceY * 47)
                    (mixed ushr 3) and 0xff
                } else 0
                pixels[y * width + x] = value.toByte()
            }
        }
        return GrayFrame(timestamp, width, height, pixels)
    }
}
