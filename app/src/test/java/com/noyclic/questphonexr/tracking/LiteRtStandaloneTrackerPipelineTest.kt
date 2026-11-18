package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtStandaloneTrackerPipelineTest {

    @Test
    fun standalonePipelineFindsTwoHandsAndPublishesTheSharedFrameContract() {
        val detector = FakePalmDetector(
            listOf(
                region(0.25f, HandSide.LEFT),
                region(0.75f, HandSide.RIGHT)
            )
        )
        val landmarker = FakeLandmarker()
        val tracker = LiteRtStandaloneTracker(detector, landmarker)

        val frame = tracker.process(cameraFrame(100L))

        assertEquals(2, frame.hands.size)
        assertEquals(2, tracker.activeTrackCount)
        assertEquals(1, detector.calls)
        assertEquals(2, landmarker.calls)
        assertEquals(HandSide.LEFT, frame.hands[0].side)
        assertEquals(HandSide.RIGHT, frame.hands[1].side)
        assertTrue(frame.hands.all { it.points.size == 21 })
        assertTrue(frame.hands.all { it.trackingId != 0L })
        assertEquals(640, frame.sourceWidthPx)
        assertEquals(480, frame.sourceHeightPx)
    }

    @Test
    fun activeRoisAreReusedBetweenScheduledPalmDetectionPasses() {
        val detector = FakePalmDetector(listOf(region(0.4f, HandSide.UNKNOWN)))
        val landmarker = FakeLandmarker()
        val tracker = LiteRtStandaloneTracker(
            palmDetector = detector,
            landmarkEstimator = landmarker,
            detectorIntervalFrames = 2
        )

        tracker.process(cameraFrame(100L))
        tracker.process(cameraFrame(120L))
        tracker.process(cameraFrame(140L))

        assertEquals(1, detector.calls)
        assertEquals(3, landmarker.calls)
        tracker.process(cameraFrame(160L))
        assertEquals(2, detector.calls)
    }

    @Test
    fun lostHandIsRemovedAndPalmDetectionImmediatelyReacquires() {
        val detector = FakePalmDetector(listOf(region(0.5f, HandSide.UNKNOWN)))
        val landmarker = FakeLandmarker(alwaysMissing = true)
        val tracker = LiteRtStandaloneTracker(
            palmDetector = detector,
            landmarkEstimator = landmarker,
            maxConsecutiveMisses = 1
        )

        val first = tracker.process(cameraFrame(100L))
        val second = tracker.process(cameraFrame(120L))

        assertTrue(first.hands.isEmpty())
        assertTrue(second.hands.isEmpty())
        assertEquals(0, tracker.activeTrackCount)
        assertEquals(2, detector.calls)
    }

    @Test
    fun invalidLandmarkPredictionsAreNotPublished() {
        val detector = FakePalmDetector(listOf(region(0.5f, HandSide.UNKNOWN)))
        val landmarker = FakeLandmarker(invalidPoints = true)
        val tracker = LiteRtStandaloneTracker(
            palmDetector = detector,
            landmarkEstimator = landmarker,
            maxConsecutiveMisses = 1
        )

        val frame = tracker.process(cameraFrame(100L))

        assertTrue(frame.hands.isEmpty())
        assertEquals(0, tracker.activeTrackCount)
    }

    @Test
    fun resetClearsIdentityAndTimestampState() {
        val detector = FakePalmDetector(listOf(region(0.5f, HandSide.UNKNOWN)))
        val tracker = LiteRtStandaloneTracker(detector, FakeLandmarker())
        val first = tracker.process(cameraFrame(100L))
        val firstId = first.hands.single().trackingId

        tracker.reset()
        val restarted = tracker.process(cameraFrame(50L))

        assertNotEquals(firstId, restarted.hands.single().trackingId)
        assertEquals(1, tracker.activeTrackCount)
    }

    private class FakePalmDetector(
        private val detections: List<LiteRtPalmRegion>
    ) : LiteRtPalmDetector {
        var calls = 0
            private set

        override fun detect(frame: LiteRtCameraFrame): LiteRtPalmDetectionBatch {
            calls += 1
            return LiteRtPalmDetectionBatch(
                regions = detections,
                preprocessingLatencyMs = 1L,
                inferenceLatencyMs = 3L
            )
        }
    }

    private class FakeLandmarker(
        private val alwaysMissing: Boolean = false,
        private val invalidPoints: Boolean = false
    ) : LiteRtHandLandmarkEstimator {
        var calls = 0
            private set

        override fun estimate(
            frame: LiteRtCameraFrame,
            region: LiteRtPalmRegion
        ): LiteRtHandLandmarkPrediction? {
            calls += 1
            if (alwaysMissing) return null
            val points = List(21) { index ->
                HandPoint(
                    x = if (invalidPoints && index == 8) Float.NaN
                    else (region.centerX + ((index % 5) - 2) * 0.015f).coerceIn(0f, 1f),
                    y = (region.centerY + ((index / 5) - 2) * 0.015f).coerceIn(0f, 1f),
                    z = 0f
                )
            }
            return LiteRtHandLandmarkPrediction(
                points = points,
                presenceScore = 0.95f,
                side = region.sideHint,
                preprocessingLatencyMs = 1L,
                inferenceLatencyMs = 5L
            )
        }
    }

    private fun region(centerX: Float, side: HandSide) = LiteRtPalmRegion(
        centerX = centerX,
        centerY = 0.5f,
        width = 0.2f,
        height = 0.3f,
        confidence = 0.9f,
        sideHint = side
    )

    private fun cameraFrame(timestampMs: Long) = LiteRtCameraFrame(
        timestampMs = timestampMs,
        widthPx = 640,
        heightPx = 480,
        argbPixels = IntArray(640 * 480)
    )
}
