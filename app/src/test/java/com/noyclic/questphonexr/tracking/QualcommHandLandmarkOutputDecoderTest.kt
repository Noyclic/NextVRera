package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommHandLandmarkOutputDecoderTest {

    @Test
    fun mapsTwentyOneNormalizedLandmarksThroughTheInverseRoiAffine() {
        val transform = centerRoiTransform()
        val outputs = tensors(
            presence = 0.96f,
            probabilityIsRight = 0.91f,
            landmarks = FloatArray(63) { index ->
                when (index % 3) {
                    0 -> 0.5f
                    1 -> 0.5f
                    else -> 0.025f
                }
            }
        )

        val decoded = assertSuccess(
            QualcommHandLandmarkOutputDecoder.decode(
                outputs = outputs,
                roiTransform = transform,
                frameTimestampMs = 1_234L,
                pipelineLatencyMs = 18L,
                preprocessingLatencyMs = 2L,
                inferenceLatencyMs = 11L
            )
        )

        assertEquals(21, decoded.prediction.points.size)
        assertEquals(HandSide.RIGHT, decoded.prediction.side)
        assertEquals(0.82f, decoded.handednessConfidence, 0.0001f)
        assertEquals(0.96f, decoded.prediction.presenceScore, 0f)
        assertEquals(2L, decoded.prediction.preprocessingLatencyMs)
        assertEquals(11L, decoded.prediction.inferenceLatencyMs)
        assertEquals(0.5f, decoded.prediction.points[0].x, 0.0001f)
        assertEquals(0.5f, decoded.prediction.points[0].y, 0.0001f)
        assertEquals(0.025f, decoded.prediction.points[0].z, 0f)
        assertTrue(decoded.prediction.worldPoints.isEmpty())
    }

    @Test
    fun decodesLeftHandAndKeepsTheHandednessDeadZoneUnknown() {
        val transform = centerRoiTransform()

        val left = assertSuccess(
            QualcommHandLandmarkOutputDecoder.decode(
                outputs = tensors(probabilityIsRight = 0.08f),
                roiTransform = transform,
                frameTimestampMs = 1L
            )
        )
        assertEquals(HandSide.LEFT, left.prediction.side)
        assertEquals(0.84f, left.handednessConfidence, 0.0001f)

        val ambiguous = assertSuccess(
            QualcommHandLandmarkOutputDecoder.decode(
                outputs = tensors(probabilityIsRight = 0.52f),
                roiTransform = transform,
                frameTimestampMs = 2L
            )
        )
        assertEquals(HandSide.UNKNOWN, ambiguous.prediction.side)
        assertEquals(0.04f, ambiguous.handednessConfidence, 0.0001f)
    }

    @Test
    fun mapsRotatedHandRoiLandmarksBackToTheSourceFrame() {
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = LiteRtPalmRegion(
                centerX = 0.5f,
                centerY = 0.5f,
                width = 0.5f,
                height = 0.5f,
                rotationRadians = (Math.PI / 2.0).toFloat()
            ),
            sourceWidthPx = 640,
            sourceHeightPx = 480,
            inputWidthPx = 256,
            inputHeightPx = 256
        )

        val decoded = assertSuccess(
            QualcommHandLandmarkOutputDecoder.decode(
                outputs = tensors(
                    landmarks = FloatArray(63) { index ->
                        if (index % 3 == 2) 0f else 0.5f
                    }
                ),
                roiTransform = transform,
                frameTimestampMs = 5L
            )
        )

        // The center is invariant under rotation; all landmarks must remain in the camera frame.
        assertEquals(0.5f, decoded.prediction.points[0].x, 0.0001f)
        assertEquals(0.5f, decoded.prediction.points[0].y, 0.0001f)
        assertTrue(decoded.prediction.points.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun rejectsAbsentHandAndMalformedTensorShapes() {
        val transform = centerRoiTransform()

        assertEquals(
            QualcommHandLandmarkDecodeRejection.NO_HAND_PRESENT,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors(presence = 0.49f),
                    transform,
                    1L
                )
            ).reason
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_TENSOR_SHAPE,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors().copy(
                        landmarks = LiteRtFloatTensor(intArrayOf(1, 20, 3), FloatArray(60))
                    ),
                    transform,
                    2L
                )
            ).reason
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_TENSOR_SHAPE,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors().copy(
                        handPresence = LiteRtFloatTensor(intArrayOf(1, 2), floatArrayOf(0.9f, 0.1f))
                    ),
                    transform,
                    3L
                )
            ).reason
        )
    }

    @Test
    fun rejectsNonFiniteScoresInvalidRangesAndCorruptLandmarks() {
        val transform = centerRoiTransform()

        assertEquals(
            QualcommHandLandmarkDecodeRejection.NON_FINITE_OUTPUT,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors().copy(
                        landmarks = LiteRtFloatTensor(
                            intArrayOf(1, 21, 3),
                            FloatArray(63) { if (it == 17) Float.NaN else 0.5f }
                        )
                    ),
                    transform,
                    1L
                )
            ).reason
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_HANDEDNESS_SCORE,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors(probabilityIsRight = 1.2f),
                    transform,
                    2L
                )
            ).reason
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_LANDMARK_COORDINATE,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors(
                        landmarks = FloatArray(63) { index ->
                            when (index % 3) {
                                0 -> 4f
                                1 -> 0.5f
                                else -> 0f
                            }
                        }
                    ),
                    transform,
                    3L
                )
            ).reason
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_PRESENCE_SCORE,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors(presence = -0.1f),
                    transform,
                    4L
                )
            ).reason
        )
    }

    @Test
    fun rejectsInvalidTimingAndOutOfFrameLandmarks() {
        val transform = centerRoiTransform()

        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_FRAME_TIMESTAMP,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(tensors(), transform, 0L)
            ).reason
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.INVALID_PIPELINE_LATENCY,
            assertRejected(
                QualcommHandLandmarkOutputDecoder.decode(
                    tensors(), transform, 1L, pipelineLatencyMs = -1L
                )
            ).reason
        )

        val edgeRoi = QualcommHandRoiTransform.fromPalmRegion(
            region = LiteRtPalmRegion(
                centerX = 0.05f,
                centerY = 0.05f,
                width = 0.2f,
                height = 0.2f
            ),
            sourceWidthPx = 640,
            sourceHeightPx = 480
        )
        val outside = QualcommHandLandmarkOutputDecoder.decode(
            outputs = tensors(
                landmarks = FloatArray(63) { index ->
                    when (index % 3) {
                        0 -> 0f
                        1 -> 0.5f
                        else -> 0f
                    }
                }
            ),
            roiTransform = edgeRoi,
            frameTimestampMs = 2L
        )
        assertEquals(
            QualcommHandLandmarkDecodeRejection.LANDMARK_OUTSIDE_SOURCE_FRAME,
            assertRejected(outside).reason
        )
    }

    private fun centerRoiTransform() = QualcommHandRoiTransform(
        sourceWidthPx = 640,
        sourceHeightPx = 480,
        inputWidthPx = 256,
        inputHeightPx = 256,
        topLeftX = 160f,
        topLeftY = 120f,
        bottomLeftX = 160f,
        bottomLeftY = 360f,
        topRightX = 480f,
        topRightY = 120f
    )

    private fun tensors(
        presence: Float = 0.95f,
        probabilityIsRight: Float = 0.9f,
        landmarks: FloatArray = FloatArray(63) { index ->
            if (index % 3 == 2) 0f else 0.5f
        }
    ) = QualcommHandLandmarkTensors(
        handPresence = LiteRtFloatTensor(intArrayOf(1, 1), floatArrayOf(presence)),
        probabilityIsRightHand = LiteRtFloatTensor(
            intArrayOf(1), floatArrayOf(probabilityIsRight)
        ),
        landmarks = LiteRtFloatTensor(intArrayOf(1, 21, 3), landmarks)
    )

    private fun assertSuccess(
        result: QualcommHandLandmarkDecodeResult
    ): QualcommHandLandmarkDecodeResult.Success {
        assertTrue("Expected success, got $result", result is QualcommHandLandmarkDecodeResult.Success)
        return result as QualcommHandLandmarkDecodeResult.Success
    }

    private fun assertRejected(
        result: QualcommHandLandmarkDecodeResult
    ): QualcommHandLandmarkDecodeResult.Rejected {
        assertTrue("Expected rejection, got $result", result is QualcommHandLandmarkDecodeResult.Rejected)
        return result as QualcommHandLandmarkDecodeResult.Rejected
    }
}
