package com.noyclic.questphonexr.tracking

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommPalmAnchorAssetLoaderTest {

    @Test
    fun parsesPinnedShapeFloat64AnchorTable() {
        val bytes = npyFixture(
            descriptor = "<f8",
            values = DoubleArray(2_944 * 4) { index ->
                when (index % 4) {
                    0, 1 -> 0.5
                    2 -> 0.75
                    else -> 0.625
                }
            }
        )

        val anchors = QualcommPalmAnchorAssetLoader.parseNpy(bytes)

        assertEquals(2_944, anchors.size)
        assertEquals(0.5f, anchors.first().offsetX, 0f)
        assertEquals(0.5f, anchors.first().offsetY, 0f)
        assertEquals(0.75f, anchors.first().scaleX, 0f)
        assertEquals(0.625f, anchors.first().scaleY, 0f)
        assertEquals(0.75f, anchors.last().scaleX, 0f)
        assertTrue(anchors.all(QualcommPalmAnchor::isValid))
    }

    @Test
    fun parsesBigEndianFloat32NpyPayload() {
        val bytes = npyFixture(
            descriptor = ">f4",
            values = FloatArray(2_944 * 4) { index ->
                when (index % 4) {
                    0 -> 0.125f
                    1 -> 0.875f
                    2 -> 0.5f
                    else -> 0.25f
                }
            },
            version = 2
        )

        val anchors = QualcommPalmAnchorAssetLoader.parseNpy(bytes)
        val sample = anchors[1]

        assertEquals(0.125f, sample.offsetX, 0f)
        assertEquals(0.875f, sample.offsetY, 0f)
        assertEquals(0.5f, sample.scaleX, 0f)
        assertEquals(0.25f, sample.scaleY, 0f)
    }

    @Test
    fun rejectsWrongShapeAndFortranOrder() {
        val wrongShape = npyFixture(
            descriptor = "<f8",
            values = DoubleArray(16) { if (it % 4 < 2) 0.5 else 1.0 },
            shape = "(4, 4)"
        )
        assertThrows(IllegalArgumentException::class.java) {
            QualcommPalmAnchorAssetLoader.parseNpy(wrongShape)
        }

        val fortran = npyFixture(
            descriptor = "<f8",
            values = DoubleArray(2_944 * 4) { if (it % 4 < 2) 0.5 else 1.0 },
            fortranOrder = true
        )
        assertThrows(IllegalArgumentException::class.java) {
            QualcommPalmAnchorAssetLoader.parseNpy(fortran)
        }
    }

    @Test
    fun rejectsMalformedHeaderTruncatedPayloadAndInvalidAnchorValues() {
        assertThrows(IllegalArgumentException::class.java) {
            QualcommPalmAnchorAssetLoader.parseNpy(byteArrayOf(1, 2, 3))
        }

        val valid = npyFixture(
            descriptor = "<f8",
            values = DoubleArray(2_944 * 4) { if (it % 4 < 2) 0.5 else 1.0 }
        )
        assertThrows(IllegalArgumentException::class.java) {
            QualcommPalmAnchorAssetLoader.parseNpy(valid.copyOf(valid.size - 1))
        }

        val invalidOffset = npyFixture(
            descriptor = "<f8",
            values = DoubleArray(2_944 * 4) { index ->
                when (index % 4) {
                    0 -> 1.25
                    1 -> 0.5
                    2, 3 -> 1.0
                    else -> 0.0
                }
            }
        )
        assertThrows(IllegalArgumentException::class.java) {
            QualcommPalmAnchorAssetLoader.parseNpy(invalidOffset)
        }

        val invalidScale = npyFixture(
            descriptor = "<f8",
            values = DoubleArray(2_944 * 4) { index ->
                when (index % 4) {
                    0, 1 -> 0.5
                    2 -> 0.0
                    else -> 1.0
                }
            }
        )
        assertThrows(IllegalArgumentException::class.java) {
            QualcommPalmAnchorAssetLoader.parseNpy(invalidScale)
        }
    }

    private fun npyFixture(
        descriptor: String,
        values: DoubleArray,
        shape: String = "(2944, 4)",
        fortranOrder: Boolean = false,
        version: Int = 1
    ): ByteArray {
        val headerBase = "{'descr': '$descriptor', 'fortran_order': $fortranOrder, 'shape': $shape, }"
        val prefixSize = if (version == 1) 10 else 12
        val padding = (16 - ((prefixSize + headerBase.length + 1) % 16)) % 16
        val header = (headerBase + " ".repeat(padding) + "\n")
            .toByteArray(StandardCharsets.US_ASCII)
        val output = ByteArrayOutputStream()
        output.write(
            byteArrayOf(
                0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
                'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte(),
                version.toByte(), 0
            )
        )
        if (version == 1) {
            output.write(
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(header.size.toShort()).array()
            )
        } else {
            output.write(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(header.size).array()
            )
        }
        output.write(header)

        val elementBytes = if (descriptor.endsWith("f4")) 4 else 8
        val order = if (descriptor.startsWith(">")) {
            ByteOrder.BIG_ENDIAN
        } else {
            ByteOrder.LITTLE_ENDIAN
        }
        val payload = ByteBuffer.allocate(values.size * elementBytes).order(order)
        values.forEach { value ->
            if (elementBytes == 4) payload.putFloat(value.toFloat()) else payload.putDouble(value)
        }
        output.write(payload.array())
        return output.toByteArray()
    }
}
