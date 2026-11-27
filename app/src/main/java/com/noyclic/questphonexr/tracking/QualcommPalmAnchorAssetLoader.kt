package com.noyclic.questphonexr.tracking

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Loads the exact palm-anchor table required by Qualcomm's MediaPipe-Hand detector.
 *
 * Source is pinned independently from the downloadable TFLite model file so the model
 * pre/post-processing assets cannot drift to a different MediaPipe anchor configuration.
 */
object QualcommPalmAnchorAssetLoader {
    const val ASSET_PATH = "qualcomm/anchors_palm.npy.base64"
    const val SOURCE_REPOSITORY = "https://github.com/zmurez/MediaPipePyTorch"
    const val SOURCE_REVISION = "65f2549ba35cd61dfd29f402f6c21882a32fabb1"
    const val SOURCE_GIT_BLOB_SHA1 = "7923b7d236050a68b98cba5ffa86b2296e34af5f"
    const val EXPECTED_NPY_BYTES = 94_336
    const val EXPECTED_ANCHOR_COUNT = 2_944
    const val EXPECTED_VALUES_PER_ANCHOR = 4

    private val MAGIC = byteArrayOf(
        0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(),
        'P'.code.toByte(), 'Y'.code.toByte()
    )

    /** Reads and parses the bundled table once during model initialization, never per frame. */
    fun load(context: Context): List<QualcommPalmAnchor> {
        val encoded = context.assets.open(ASSET_PATH).bufferedReader(StandardCharsets.US_ASCII).use {
            it.readText().trim()
        }
        return parseBase64(encoded)
    }

    /** Pure entry point used by deterministic JVM tests and non-Android tooling. */
    fun parseBase64(encoded: String): List<QualcommPalmAnchor> {
        require(encoded.isNotBlank()) { "Qualcomm palm anchor asset is empty." }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Qualcomm palm anchor asset is not valid Base64.", error)
        }
        require(bytes.size == EXPECTED_NPY_BYTES) {
            "Qualcomm palm anchor asset has ${bytes.size} bytes; expected $EXPECTED_NPY_BYTES."
        }
        return parseNpy(bytes)
    }

    /** Reads NumPy v1/v2/v3 .npy files with float32/float64 C-order anchor data. */
    fun parseNpy(npy: ByteArray): List<QualcommPalmAnchor> {
        require(npy.size >= 10) { "Qualcomm palm anchor NPY header is truncated." }
        require(MAGIC.indices.all { npy[it] == MAGIC[it] }) {
            "Qualcomm palm anchor NPY magic is invalid."
        }

        val majorVersion = npy[6].toInt() and 0xff
        val lengthFieldBytes = when (majorVersion) {
            1 -> 2
            2, 3 -> 4
            else -> throw IllegalArgumentException("Unsupported NumPy file version ${majorVersion}.")
        }
        val headerLengthOffset = 8
        require(npy.size >= headerLengthOffset + lengthFieldBytes) {
            "Qualcomm palm anchor NPY header length is truncated."
        }

        var headerLength = 0L
        repeat(lengthFieldBytes) { index ->
            headerLength = headerLength or (
                (npy[headerLengthOffset + index].toLong() and 0xffL) shl (8 * index)
            )
        }
        require(headerLength in 1L..65_536L) { "Qualcomm palm anchor NPY header length is invalid." }

        val headerStart = headerLengthOffset + lengthFieldBytes
        val dataOffsetLong = headerStart.toLong() + headerLength
        require(dataOffsetLong <= npy.size.toLong()) {
            "Qualcomm palm anchor NPY header exceeds the file size."
        }
        val header = String(
            npy,
            headerStart,
            headerLength.toInt(),
            StandardCharsets.US_ASCII
        )

        val descriptor = HEADER_DESCRIPTOR.find(header)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Qualcomm palm anchor NPY dtype is missing.")
        val shapeMatch = HEADER_SHAPE.find(header)
            ?: throw IllegalArgumentException("Qualcomm palm anchor NPY shape is missing.")
        val anchorCount = shapeMatch.groupValues[1].toIntOrNull()
            ?: throw IllegalArgumentException("Qualcomm palm anchor count is invalid.")
        val valuesPerAnchor = shapeMatch.groupValues[2].toIntOrNull()
            ?: throw IllegalArgumentException("Qualcomm palm anchor row size is invalid.")

        require(anchorCount == EXPECTED_ANCHOR_COUNT &&
            valuesPerAnchor == EXPECTED_VALUES_PER_ANCHOR
        ) {
            "Qualcomm palm anchors must have shape ($EXPECTED_ANCHOR_COUNT, $EXPECTED_VALUES_PER_ANCHOR); " +
                "found ($anchorCount, $valuesPerAnchor)."
        }
        require(HEADER_FORTRAN_ORDER.find(header)?.groupValues?.get(1) == "False") {
            "Qualcomm palm anchors must use NumPy C-order layout."
        }

        val elementBytes = when (descriptor.takeLast(2)) {
            "f4" -> 4
            "f8" -> 8
            else -> throw IllegalArgumentException(
                "Unsupported Qualcomm palm anchor dtype '$descriptor'; expected float32/float64."
            )
        }
        val byteOrder = when (descriptor.first()) {
            '<', '|' -> ByteOrder.LITTLE_ENDIAN
            '>' -> ByteOrder.BIG_ENDIAN
            '=' -> ByteOrder.nativeOrder()
            else -> throw IllegalArgumentException(
                "Unsupported Qualcomm palm anchor byte order in '$descriptor'."
            )
        }

        val elementCount = anchorCount.toLong() * valuesPerAnchor
        val dataBytes = elementCount * elementBytes
        require(dataOffsetLong + dataBytes == npy.size.toLong()) {
            "Qualcomm palm anchor payload length does not match its NumPy shape."
        }

        val data = ByteBuffer.wrap(
            npy,
            dataOffsetLong.toInt(),
            dataBytes.toInt()
        ).order(byteOrder)
        val anchors = ArrayList<QualcommPalmAnchor>(anchorCount)
        repeat(anchorCount) { row ->
            val offsetX = readFloat(data, elementBytes)
            val offsetY = readFloat(data, elementBytes)
            val scaleX = readFloat(data, elementBytes)
            val scaleY = readFloat(data, elementBytes)
            val anchor = QualcommPalmAnchor(offsetX, offsetY, scaleX, scaleY)
            require(anchor.isValid()) {
                "Qualcomm palm anchor row $row contains invalid offset or scale values."
            }
            anchors += anchor
        }
        return anchors
    }

    private fun readFloat(data: ByteBuffer, elementBytes: Int): Float =
        if (elementBytes == 4) data.float else data.double.toFloat()

    private val HEADER_DESCRIPTOR = Regex("""['"]descr['"]\s*:\s*['"]([<>=|]f[48])['"]""")
    private val HEADER_SHAPE = Regex("""['"]shape['"]\s*:\s*\(\s*(\d+)\s*,\s*(\d+)\s*,?\s*\)""")
    private val HEADER_FORTRAN_ORDER = Regex("""['"]fortran_order['"]\s*:\s*(True|False)""")
}
