package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommPalmInputPreprocessorTest {

    @Test
    fun resizesRgbToNormalizedNhwcAndLeavesSymmetricLetterboxBlack() {
        val source = intArrayOf(
            0xffff0000.toInt(),
            0xff0000ff.toInt()
        )
        val transform = LiteRtPalmInputTransform.centeredLetterbox(
            sourceWidthPx = 2,
            sourceHeightPx = 1,
            inputWidthPx = 4,
            inputHeightPx = 4
        )
        val output = FloatArray(4 * 4 * 3) { -1f }

        assertTrue(
            QualcommPalmInputPreprocessor.writeNhwcRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNhwc = output
            )
        )

        assertEquals(4, transform.contentWidthPx)
        assertEquals(2, transform.contentHeightPx)
        assertEquals(1, transform.padTopPx)
        assertEquals(0f, output[0], 0f)
        assertEquals(0f, output[3], 0f)
        assertEquals(0f, output[6], 0f)
        assertEquals(0f, output[9], 0f)

        val firstContentPixel = (transform.padTopPx * 4) * 3
        val lastContentPixel = (transform.padTopPx * 4 + 3) * 3
        assertEquals(1f, output[firstContentPixel], 0.0001f)
        assertEquals(0f, output[firstContentPixel + 1], 0.0001f)
        assertEquals(0f, output[firstContentPixel + 2], 0.0001f)
        assertEquals(0f, output[lastContentPixel], 0.0001f)
        assertEquals(0f, output[lastContentPixel + 1], 0.0001f)
        assertEquals(1f, output[lastContentPixel + 2], 0.0001f)

        val bottomBarStart = (3 * 4) * 3
        assertEquals(0f, output[bottomBarStart], 0f)
        assertEquals(0f, output[bottomBarStart + 1], 0f)
        assertEquals(0f, output[bottomBarStart + 2], 0f)
    }

    @Test
    fun writesSameRgbSamplesAsNchwPlanes() {
        val source = intArrayOf(0xff804020.toInt())
        val transform = LiteRtPalmInputTransform.centeredLetterbox(1, 1, 1, 1)
        val output = FloatArray(3)

        assertTrue(
            QualcommPalmInputPreprocessor.writeNchwRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNchw = output
            )
        )

        assertEquals(128f / 255f, output[0], 0.00001f)
        assertEquals(64f / 255f, output[1], 0.00001f)
        assertEquals(32f / 255f, output[2], 0.00001f)
    }

    @Test
    fun rotatesRawCameraPixelsIntoTheUprightDetectorFrame() {
        val source = intArrayOf(
            gray(1), gray(2),
            gray(3), gray(4),
            gray(5), gray(6)
        )
        val transform = LiteRtPalmInputTransform.centeredLetterbox(
            sourceWidthPx = 3,
            sourceHeightPx = 2,
            inputWidthPx = 3,
            inputHeightPx = 2
        )
        val output = FloatArray(3 * 2 * 3)

        assertTrue(
            QualcommPalmInputPreprocessor.writeNhwcRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNhwc = output,
                sourceRotationDegrees = 90
            )
        )

        val expected = floatArrayOf(5f, 3f, 1f, 6f, 4f, 2f)
        expected.forEachIndexed { index, value ->
            val offset = index * 3
            assertEquals(value / 255f, output[offset], 0.00001f)
            assertEquals(value / 255f, output[offset + 1], 0.00001f)
            assertEquals(value / 255f, output[offset + 2], 0.00001f)
        }
    }

    @Test
    fun usesFloorDimensionsAndTheSameOddPaddingSplitAsQualcommResizePad() {
        val transform = LiteRtPalmInputTransform.centeredLetterbox(
            sourceWidthPx = 1_001,
            sourceHeightPx = 333,
            inputWidthPx = 256,
            inputHeightPx = 256
        )

        assertEquals(256, transform.contentWidthPx)
        assertEquals(85, transform.contentHeightPx)
        assertEquals(0, transform.padLeftPx)
        assertEquals(85, transform.padTopPx)
    }

    @Test
    fun decoderMappingUsesTheSameNominalResizeScaleAsQualcommResizePad() {
        val transform = LiteRtPalmInputTransform.centeredLetterbox(
            sourceWidthPx = 1_001,
            sourceHeightPx = 333,
            inputWidthPx = 256,
            inputHeightPx = 256
        )

        val source = transform.toSourcePixel(128f, 128f)!!
        val expectedScale = 256f / 1_001f
        assertEquals((128f - transform.padLeftPx) / expectedScale, source.first, 0.0001f)
        assertEquals((128f - transform.padTopPx) / expectedScale, source.second, 0.0001f)
    }

    @Test
    fun rejectsUnsupportedRotationAndShortBuffersWithoutModifyingOutput() {
        val transform = LiteRtPalmInputTransform.centeredLetterbox(2, 2, 2, 2)
        val output = FloatArray(12) { 7f }

        assertFalse(
            QualcommPalmInputPreprocessor.writeNhwcRgbFloat(
                sourceArgb = IntArray(4),
                transform = transform,
                destinationNhwc = output,
                sourceRotationDegrees = 45
            )
        )
        assertFalse(
            QualcommPalmInputPreprocessor.writeNhwcRgbFloat(
                sourceArgb = IntArray(3),
                transform = transform,
                destinationNhwc = output
            )
        )
        assertFalse(
            QualcommPalmInputPreprocessor.writeNhwcRgbFloat(
                sourceArgb = IntArray(4),
                transform = transform,
                destinationNhwc = FloatArray(11)
            )
        )

        assertTrue(output.all { it == 7f })
    }

    private fun gray(value: Int): Int =
        (0xff shl 24) or (value shl 16) or (value shl 8) or value
}
