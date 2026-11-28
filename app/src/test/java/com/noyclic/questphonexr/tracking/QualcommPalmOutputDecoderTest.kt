package com.noyclic.questphonexr.tracking

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommPalmOutputDecoderTest {

    @Test
    fun decodesAnchorsSigmoidAndQualcommRotatedHandRoi() {
        val result = decode(
            anchors = listOf(anchor()),
            coordinates = floatArrayOf(
                0f, 0f, 40f, 60f,
                -18f, 2f, -8f, -8f, 2f, -18f,
                0f, 22f, 7f, 22f, 12f, 22f, 17f, 32f
            ),
            scores = floatArrayOf(3f)
        )

        val success = assertSuccess(result)
        assertEquals(1, success.candidatesAfterThreshold)
        assertEquals(1, success.candidatesAfterNms)
        val hand = success.regions.single()

        // Letterbox is 256x192 at y=32 for a 640x480 source frame.
        // The model-space box (108,98)-(148,158) maps to (270,165)-(370,315).
        // Qualcomm AI Hub's MediaPipeHandApp uses a 0.5 box-width directional
        // offset along the wrist-to-middle-finder vector and scales W/H by 2.5.
        assertEquals(0.55524f, hand.centerX, 0.0002f)
        assertEquals(0.42634f, hand.centerY, 0.0002f)
        assertEquals(0.390625f, hand.width, 0.0001f)
        assertEquals(0.78125f, hand.height, 0.0001f)
        assertEquals((PI / 4.0).toFloat(), hand.rotationRadians, 0.0002f)
        assertEquals(1f / (1f + kotlin.math.exp(-3f)), hand.confidence, 0.0001f)
    }

    @Test
    fun acceptsBothSupportedCoordinateRanksAndScoreRanks() {
        val coordinates = floatArrayOf(
            0f, 0f, 40f, 60f,
            -18f, 2f, -8f, -8f, 2f, -18f,
            0f, 22f, 7f, 22f, 12f, 22f, 17f, 32f
        )
        val result = QualcommPalmOutputDecoder.decode(
            outputs = QualcommPalmDetectorTensors(
                coordinates = LiteRtFloatTensor(intArrayOf(1, 1, 9, 2), coordinates),
                scores = LiteRtFloatTensor(intArrayOf(1, 1, 1), floatArrayOf(3f))
            ),
            anchors = listOf(anchor()),
            inputTransform = transform()
        )

        assertEquals(1, assertSuccess(result).regions.size)
    }

    @Test
    fun supportsDynamicAnchorCountsAndSuppressesOverlappingPalmBoxes() {
        val coordinates = FloatArray(3 * 18)
        repeat(3) { index ->
            val offset = index * 18
            coordinates[offset] = 0f
            coordinates[offset + 1] = 0f
            coordinates[offset + 2] = 40f
            coordinates[offset + 3] = 60f
            val keypoints = floatArrayOf(
                -18f, 2f,
                -8f, -8f,
                2f, -18f,
                0f, 22f,
                7f, 22f,
                12f, 22f,
                17f, 32f
            )
            keypoints.copyInto(coordinates, offset + 4)
        }
        val result = QualcommPalmOutputDecoder.decode(
            outputs = QualcommPalmDetectorTensors(
                coordinates = LiteRtFloatTensor(intArrayOf(1, 3, 18), coordinates),
                scores = LiteRtFloatTensor(intArrayOf(1, 3), floatArrayOf(4f, 3.5f, 3f))
            ),
            anchors = listOf(anchor(0.5f), anchor(0.5f), anchor(0.8f)),
            inputTransform = transform(),
            maxHands = 2
        )

        val success = assertSuccess(result)
        assertEquals(3, success.candidatesAfterThreshold)
        assertEquals(2, success.candidatesAfterNms)
        assertEquals(2, success.regions.size)
        assertTrue(success.regions[1].centerX > success.regions[0].centerX + 0.2f)
    }

    @Test
    fun rejectsTensorAndAnchorCountMismatchInsteadOfGuessingAnchorLayout() {
        val outputs = QualcommPalmDetectorTensors(
            coordinates = LiteRtFloatTensor(intArrayOf(1, 2, 18), FloatArray(36)),
            scores = LiteRtFloatTensor(intArrayOf(1, 2), floatArrayOf(3f, 3f))
        )

        assertEquals(
            QualcommPalmDecodeRejection.ANCHOR_COUNT_MISMATCH,
            assertRejected(
                QualcommPalmOutputDecoder.decode(outputs, listOf(anchor()), transform())
            ).reason
        )
        assertEquals(
            QualcommPalmDecodeRejection.INVALID_SCORE_SHAPE,
            assertRejected(
                QualcommPalmOutputDecoder.decode(
                    outputs.copy(
                        scores = LiteRtFloatTensor(intArrayOf(1, 1, 2), FloatArray(2))
                    ),
                    listOf(anchor(), anchor()),
                    transform()
                )
            ).reason
        )
        assertEquals(
            QualcommPalmDecodeRejection.INVALID_COORDINATE_SHAPE,
            assertRejected(
                QualcommPalmOutputDecoder.decode(
                    outputs.copy(
                        coordinates = LiteRtFloatTensor(intArrayOf(1, 2, 17), FloatArray(34))
                    ),
                    listOf(anchor(), anchor()),
                    transform()
                )
            ).reason
        )
    }

    @Test
    fun rejectsInvalidAnchorsNonFiniteOutputsAndThresholds() {
        val validOutputs = QualcommPalmDetectorTensors(
            coordinates = LiteRtFloatTensor(intArrayOf(1, 1, 18), FloatArray(18)),
            scores = LiteRtFloatTensor(intArrayOf(1, 1), floatArrayOf(3f))
        )

        assertEquals(
            QualcommPalmDecodeRejection.INVALID_ANCHOR,
            assertRejected(
                QualcommPalmOutputDecoder.decode(
                    validOutputs,
                    listOf(QualcommPalmAnchor(0.5f, 0.5f, 0f, 1f)),
                    transform()
                )
            ).reason
        )
        assertEquals(
            QualcommPalmDecodeRejection.NON_FINITE_OUTPUT,
            assertRejected(
                QualcommPalmOutputDecoder.decode(
                    validOutputs.copy(
                        coordinates = validOutputs.coordinates.copy(
                            values = FloatArray(18).also { it[3] = Float.NaN }
                        )
                    ),
                    listOf(anchor()),
                    transform()
                )
            ).reason
        )
        assertEquals(
            QualcommPalmDecodeRejection.INVALID_THRESHOLDS,
            assertRejected(
                QualcommPalmOutputDecoder.decode(
                    validOutputs,
                    listOf(anchor()),
                    transform(),
                    scoreThreshold = Float.NaN
                )
            ).reason
        )
    }

    @Test
    fun belowThresholdLogitReturnsNoHandsWithoutInventingFallbacks() {
        val result = decode(
            anchors = listOf(anchor()),
            coordinates = FloatArray(18),
            scores = floatArrayOf(2f)
        )

        val success = assertSuccess(result)
        assertTrue(success.regions.isEmpty())
        assertEquals(0, success.candidatesAfterThreshold)
        assertEquals(0, success.candidatesAfterNms)
    }

    @Test
    fun letterboxGeometryMapsCoordinatesBackToTheOriginalCameraFrame() {
        val geometry = transform()
        assertEquals(256, geometry.contentWidthPx)
        assertEquals(192, geometry.contentHeightPx)
        assertEquals(0, geometry.padLeftPx)
        assertEquals(32, geometry.padTopPx)

        val center = geometry.toSourcePixel(128f, 128f)!!
        assertEquals(320f, center.first, 0.001f)
        assertEquals(240f, center.second, 0.001f)
    }

    private fun decode(
        anchors: List<QualcommPalmAnchor>,
        coordinates: FloatArray,
        scores: FloatArray
    ) = QualcommPalmOutputDecoder.decode(
        outputs = QualcommPalmDetectorTensors(
            coordinates = LiteRtFloatTensor(
                intArrayOf(1, anchors.size, 18),
                coordinates
            ),
            scores = LiteRtFloatTensor(intArrayOf(1, anchors.size), scores)
        ),
        anchors = anchors,
        inputTransform = transform()
    )

    private fun transform() =
        LiteRtPalmInputTransform.centeredLetterbox(640, 480)

    private fun anchor(centerX: Float = 0.5f) =
        QualcommPalmAnchor(centerX, 0.5f, 1f, 1f)

    private fun assertSuccess(
        result: QualcommPalmDecodeResult
    ): QualcommPalmDecodeResult.Success {
        assertTrue("Expected success, got $result", result is QualcommPalmDecodeResult.Success)
        return result as QualcommPalmDecodeResult.Success
    }

    private fun assertRejected(
        result: QualcommPalmDecodeResult
    ): QualcommPalmDecodeResult.Rejected {
        assertTrue("Expected rejection, got $result", result is QualcommPalmDecodeResult.Rejected)
        return result as QualcommPalmDecodeResult.Rejected
    }
}
