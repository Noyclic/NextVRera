package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommStandaloneModelAdaptersTest {

    @Test
    fun palmAdapterPreprocessesFrameAndReturnsTwoHandCapableDetectorContract() {
        var receivedElements = 0
        var receivedWhitePixel = false
        val engine = QualcommPalmInferenceEngine { input ->
            receivedElements = input.size
            receivedWhitePixel = input.take(3).all { it == 1f }
            palmOutput()
        }
        val detector = QualcommLiteRtPalmDetector(
            engine = engine,
            anchors = anchors()
        )

        val batch = detector.detect(frame())

        assertEquals(256 * 256 * 3, receivedElements)
        assertTrue("Expected RGB white pixel in NCHW input", receivedWhitePixel)
        assertEquals(1, batch.regions.size)
        assertTrue(batch.regions.single().confidence > 0.9f)
        assertTrue(batch.preprocessingLatencyMs >= 0L)
        assertTrue(batch.inferenceLatencyMs >= 0L)
    }

    @Test
    fun landmarkAdapterMaps21PointsAndPreservesModelHandedness() {
        var receivedElements = 0
        var receivedWhitePixel = false
        val engine = QualcommHandLandmarkInferenceEngine { input ->
            receivedElements = input.size
            receivedWhitePixel = input.take(3).all { it == 1f }
            landmarkOutput(presence = 0.99f, probabilityIsRight = 0.9f)
        }
        val estimator = QualcommLiteRtHandLandmarkEstimator(engine)

        val prediction = estimator.estimate(
            frame(),
            LiteRtPalmRegion(
                centerX = 0.5f,
                centerY = 0.5f,
                width = 0.5f,
                height = 0.5f
            )
        )

        assertNotNull(prediction)
        assertEquals(256 * 256 * 3, receivedElements)
        assertTrue("Expected RGB white pixel in NCHW crop", receivedWhitePixel)
        assertEquals(21, prediction!!.points.size)
        assertEquals(HandSide.RIGHT, prediction.side)
        assertTrue(prediction.points.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun landmarkAdapterTreatsLowPresenceAsNoHandNotAsModelFailure() {
        val estimator = QualcommLiteRtHandLandmarkEstimator(
            engine = QualcommHandLandmarkInferenceEngine {
                landmarkOutput(presence = 0.1f, probabilityIsRight = 0.9f)
            }
        )

        val prediction = estimator.estimate(
            frame(),
            LiteRtPalmRegion(0.5f, 0.5f, 0.5f, 0.5f)
        )

        assertNull(prediction)
    }

    @Test
    fun landmarkAdapterSurfacesMalformedTensorContract() {
        val estimator = QualcommLiteRtHandLandmarkEstimator(
            engine = QualcommHandLandmarkInferenceEngine {
                QualcommHandLandmarkInferenceOutput(
                    handPresence = LiteRtFloatTensor(intArrayOf(1), floatArrayOf(1f)),
                    probabilityIsRightHand = LiteRtFloatTensor(intArrayOf(1), floatArrayOf(0.9f)),
                    landmarks = LiteRtFloatTensor(intArrayOf(1, 20, 3), FloatArray(60))
                )
            }
        )

        try {
            estimator.estimate(
                frame(),
                LiteRtPalmRegion(0.5f, 0.5f, 0.5f, 0.5f)
            )
            throw AssertionError("Malformed output must not be reported as a missing hand.")
        } catch (expected: QualcommModelOutputException) {
            assertTrue(expected.message!!.contains("INVALID_TENSOR_SHAPE"))
        }
    }

    private fun frame() = LiteRtCameraFrame(
        timestampMs = 100L,
        widthPx = 64,
        heightPx = 64,
        argbPixels = IntArray(64 * 64) { 0xffffffff.toInt() }
    )

    private fun anchors(): List<QualcommPalmAnchor> =
        List(QualcommPalmAnchorAssetLoader.EXPECTED_ANCHOR_COUNT) {
            QualcommPalmAnchor(0.5f, 0.5f, 1f, 1f)
        }

    private fun palmOutput(): QualcommPalmInferenceOutput {
        val coordinates = FloatArray(
            QualcommPalmAnchorAssetLoader.EXPECTED_ANCHOR_COUNT *
                QualcommPalmOutputDecoder.COORDINATES_PER_ANCHOR
        )
        // A single palm: center=(128,128), size=(80,80), wrist=(118,128),
        // middle-finder=(138,128). Other anchors stay below score threshold.
        coordinates[0] = 0f
        coordinates[1] = 0f
        coordinates[2] = 80f
        coordinates[3] = 80f
        coordinates[4] = -10f
        coordinates[5] = 0f
        coordinates[8] = 10f
        coordinates[9] = 0f
        val scores = FloatArray(QualcommPalmAnchorAssetLoader.EXPECTED_ANCHOR_COUNT) { -100f }
        scores[0] = 3f
        return QualcommPalmInferenceOutput(
            coordinates = LiteRtFloatTensor(
                intArrayOf(
                    1,
                    QualcommPalmAnchorAssetLoader.EXPECTED_ANCHOR_COUNT,
                    QualcommPalmOutputDecoder.COORDINATES_PER_ANCHOR
                ),
                coordinates
            ),
            scores = LiteRtFloatTensor(
                intArrayOf(1, QualcommPalmAnchorAssetLoader.EXPECTED_ANCHOR_COUNT),
                scores
            )
        )
    }

    private fun landmarkOutput(
        presence: Float,
        probabilityIsRight: Float
    ) = QualcommHandLandmarkInferenceOutput(
        handPresence = LiteRtFloatTensor(intArrayOf(1), floatArrayOf(presence)),
        probabilityIsRightHand = LiteRtFloatTensor(
            intArrayOf(1),
            floatArrayOf(probabilityIsRight)
        ),
        landmarks = LiteRtFloatTensor(
            intArrayOf(1, 21, 3),
            FloatArray(21 * 3).also { values ->
                repeat(21) { index ->
                    values[index * 3] = 0.5f
                    values[index * 3 + 1] = 0.5f
                    values[index * 3 + 2] = 0f
                }
            }
        )
    )
}
