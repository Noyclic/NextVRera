package com.noyclic.hardwareprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantizedTensorComparatorTest {
    @Test
    fun identicalUnsignedOutputsArePerfectMatches() {
        val values = byteArrayOf(0, 12, 64, 100, -1)
        val result = QuantizedTensorComparator.compare(
            values,
            values.copyOf(),
            signed = false,
            scale = 0.125f,
            zeroPoint = 128
        )

        assertEquals(1.0, result.cosineSimilarity, 1e-12)
        assertEquals(0.0, result.meanAbsoluteError, 0.0)
        assertEquals(0.0, result.maxAbsoluteError, 0.0)
        assertEquals(100.0, result.exactMatchPercent, 0.0)
        assertTrue(result.top1Agreement)
        assertEquals(4, result.referenceTopIndex)
    }

    @Test
    fun unsignedBytesAreComparedAsZeroTo255() {
        val result = QuantizedTensorComparator.compare(
            byteArrayOf(0, 100, -1),
            byteArrayOf(0, 99, -1),
            signed = false,
            scale = 0.5f,
            zeroPoint = 128
        )

        assertEquals(2.0 / 3.0, result.exactMatchPercent / 100.0, 1e-12)
        assertEquals(0.5 / 3.0, result.meanAbsoluteError, 1e-9)
        assertEquals(0.5, result.maxAbsoluteError, 1e-9)
        assertTrue(result.top1Agreement)
        assertEquals(2, result.referenceTopIndex)
    }

    @Test
    fun signedBytesUseSignedInt8Values() {
        val result = QuantizedTensorComparator.compare(
            byteArrayOf(-128, 0, 127),
            byteArrayOf(-128, 0, 126),
            signed = true,
            scale = 0.25f,
            zeroPoint = 0
        )

        assertEquals(2, result.referenceTopIndex)
        assertEquals(2, result.candidateTopIndex)
        assertTrue(result.top1Agreement)
        assertEquals(0.25 / 3.0, result.meanAbsoluteError, 1e-9)
        assertEquals(0.25, result.maxAbsoluteError, 1e-9)
    }

    @Test
    fun changedWinnerIsDetected() {
        val result = QuantizedTensorComparator.compare(
            byteArrayOf(10, 20, 30),
            byteArrayOf(10, 31, 30),
            signed = false,
            scale = 1f,
            zeroPoint = 0
        )

        assertFalse(result.top1Agreement)
        assertEquals(1, result.candidateTopIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedTensorSizesAreRejected() {
        QuantizedTensorComparator.compare(
            byteArrayOf(1, 2),
            byteArrayOf(1),
            signed = false,
            scale = 1f,
            zeroPoint = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTensorsAreRejected() {
        QuantizedTensorComparator.compare(
            byteArrayOf(),
            byteArrayOf(),
            signed = false,
            scale = 1f,
            zeroPoint = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveQuantizationScaleIsRejected() {
        QuantizedTensorComparator.compare(
            byteArrayOf(1),
            byteArrayOf(1),
            signed = false,
            scale = 0f,
            zeroPoint = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeUnsignedZeroPointIsRejected() {
        QuantizedTensorComparator.compare(
            byteArrayOf(1),
            byteArrayOf(1),
            signed = false,
            scale = 0.5f,
            zeroPoint = 256
        )
    }
}
