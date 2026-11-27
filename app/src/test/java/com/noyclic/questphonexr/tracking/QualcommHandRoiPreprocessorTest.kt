package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommHandRoiPreprocessorTest {

    @Test
    fun zeroRotationCropMatchesTheThreePointAffineGeometry() {
        val source = IntArray(16) { index -> gray(index + 1) }
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = region(rotation = 0f),
            sourceWidthPx = 4,
            sourceHeightPx = 4,
            inputWidthPx = 3,
            inputHeightPx = 3
        )
        val output = FloatArray(3 * 3 * 3)

        assertTrue(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNhwc = output
            )
        )

        val expected = intArrayOf(6, 7, 8, 10, 11, 12, 14, 15, 16)
        expected.forEachIndexed { index, value ->
            val offset = index * 3
            assertEquals(value / 255f, output[offset], 0.00001f)
            assertEquals(value / 255f, output[offset + 1], 0.00001f)
            assertEquals(value / 255f, output[offset + 2], 0.00001f)
        }
    }

    @Test
    fun ninetyDegreePalmRotationUprightsTheHandInsideTheCrop() {
        val source = IntArray(16) { index -> gray(index + 1) }
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = region(rotation = (Math.PI / 2.0).toFloat()),
            sourceWidthPx = 4,
            sourceHeightPx = 4,
            inputWidthPx = 3,
            inputHeightPx = 3
        )
        val output = FloatArray(3 * 3 * 3)

        assertTrue(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNhwc = output
            )
        )

        val expected = intArrayOf(8, 12, 16, 7, 11, 15, 6, 10, 14)
        expected.forEachIndexed { index, value ->
            assertEquals(value / 255f, output[index * 3], 0.00001f)
        }
    }

    @Test
    fun mapsAnUprightRoiBackToTheRawCameraBufferRotation() {
        val source = intArrayOf(
            gray(1), gray(2),
            gray(3), gray(4),
            gray(5), gray(6)
        )
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = LiteRtPalmRegion(
                centerX = 0.5f,
                centerY = 0.5f,
                width = 1f,
                height = 1f,
                rotationRadians = 0f
            ),
            sourceWidthPx = 3,
            sourceHeightPx = 2,
            inputWidthPx = 3,
            inputHeightPx = 2
        )
        val output = FloatArray(3 * 2 * 3)

        assertTrue(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNhwc = output,
                sourceRotationDegrees = 90
            )
        )

        val expected = intArrayOf(5, 3, 1, 6, 4, 2)
        expected.forEachIndexed { index, value ->
            assertEquals(value / 255f, output[index * 3], 0.00001f)
        }
    }

    @Test
    fun outOfFrameRoiUsesBlackBorderInsteadOfClampingTheCrop() {
        val source = IntArray(16) { index -> gray(index + 1) }
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = LiteRtPalmRegion(
                centerX = 0.125f,
                centerY = 0.125f,
                width = 0.5f,
                height = 0.5f,
                rotationRadians = 0f
            ),
            sourceWidthPx = 4,
            sourceHeightPx = 4,
            inputWidthPx = 2,
            inputHeightPx = 2
        )
        val output = FloatArray(2 * 2 * 3)

        assertTrue(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNhwc = output
            )
        )

        assertEquals(0.25f / 255f, output[0], 0.00001f)
        assertEquals(0.25f / 255f, output[3], 0.00001f)
        assertEquals(0.25f / 255f, output[6], 0.00001f)
    }

    @Test
    fun writesTheSameRoiInNchwChannelPlanes() {
        val source = intArrayOf(
            0xff804020.toInt(), 0xff102030.toInt(),
            0xff405060.toInt(), 0xff708090.toInt()
        )
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = LiteRtPalmRegion(
                centerX = 0.5f,
                centerY = 0.5f,
                width = 1f,
                height = 1f,
                rotationRadians = 0f
            ),
            sourceWidthPx = 2,
            sourceHeightPx = 2,
            inputWidthPx = 2,
            inputHeightPx = 2
        )
        val output = FloatArray(2 * 2 * 3)

        assertTrue(
            QualcommHandRoiPreprocessor.writeNchwRgbFloat(
                sourceArgb = source,
                transform = transform,
                destinationNchw = output
            )
        )

        assertEquals(128f / 255f, output[0], 0.00001f)
        assertEquals(64f / 255f, output[4], 0.00001f)
        assertEquals(32f / 255f, output[8], 0.00001f)
        assertEquals(16f / 255f, output[1], 0.00001f)
    }

    @Test
    fun rejectsInvalidCameraRotationAndShortPixelBuffersWithoutWriting() {
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = region(),
            sourceWidthPx = 4,
            sourceHeightPx = 4,
            inputWidthPx = 3,
            inputHeightPx = 3
        )
        val output = FloatArray(3 * 3 * 3) { -1f }

        assertFalse(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = IntArray(16),
                transform = transform,
                destinationNhwc = output,
                sourceRotationDegrees = 45
            )
        )
        assertFalse(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = IntArray(15),
                transform = transform,
                destinationNhwc = output
            )
        )
        assertFalse(
            QualcommHandRoiPreprocessor.writeNhwcRgbFloat(
                sourceArgb = IntArray(16),
                transform = transform,
                destinationNhwc = FloatArray(26)
            )
        )
        assertTrue(output.all { it == -1f })
    }

    private fun region(rotation: Float = 0f) = LiteRtPalmRegion(
        centerX = 0.5f,
        centerY = 0.5f,
        width = 0.5f,
        height = 0.5f,
        rotationRadians = rotation,
        confidence = 0.95f
    )

    private fun gray(value: Int): Int =
        (0xff shl 24) or (value shl 16) or (value shl 8) or value
}
