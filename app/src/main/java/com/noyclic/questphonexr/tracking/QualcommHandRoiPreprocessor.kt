package com.noyclic.questphonexr.tracking

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Inverse affine mapping for one upright, square landmark-model crop. */
data class QualcommHandRoiTransform(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val inputWidthPx: Int,
    val inputHeightPx: Int,
    val topLeftX: Float,
    val topLeftY: Float,
    val bottomLeftX: Float,
    val bottomLeftY: Float,
    val topRightX: Float,
    val topRightY: Float
) {
    init {
        require(sourceWidthPx > 0 && sourceHeightPx > 0)
        require(inputWidthPx > 1 && inputHeightPx > 1)
        require(
            listOf(
                topLeftX, topLeftY, bottomLeftX, bottomLeftY, topRightX, topRightY
            ).all(Float::isFinite)
        )
    }

    /** Maps landmark-model pixel coordinates back into the upright source camera frame. */
    fun toSourcePixel(modelX: Float, modelY: Float): Pair<Float, Float>? {
        if (!modelX.isFinite() || !modelY.isFinite()) return null
        val u = modelX / (inputWidthPx - 1).toFloat()
        val v = modelY / (inputHeightPx - 1).toFloat()
        return mapUnitSquare(u, v)
    }

    fun toSourceNormalized(modelX: Float, modelY: Float): Pair<Float, Float>? {
        val point = toSourcePixel(modelX, modelY) ?: return null
        return point.first / sourceWidthPx to point.second / sourceHeightPx
    }

    internal fun sourceXAt(outputX: Int, outputY: Int): Float {
        val u = outputX.toFloat() / (inputWidthPx - 1)
        val v = outputY.toFloat() / (inputHeightPx - 1)
        return topLeftX +
            u * (topRightX - topLeftX) +
            v * (bottomLeftX - topLeftX)
    }

    internal fun sourceYAt(outputX: Int, outputY: Int): Float {
        val u = outputX.toFloat() / (inputWidthPx - 1)
        val v = outputY.toFloat() / (inputHeightPx - 1)
        return topLeftY +
            u * (topRightY - topLeftY) +
            v * (bottomLeftY - topLeftY)
    }

    private fun mapUnitSquare(u: Float, v: Float): Pair<Float, Float> {
        val bottomVectorX = bottomLeftX - topLeftX
        val bottomVectorY = bottomLeftY - topLeftY
        val rightVectorX = topRightX - topLeftX
        val rightVectorY = topRightY - topLeftY
        return (
            topLeftX + u * rightVectorX + v * bottomVectorX
            ) to (
            topLeftY + u * rightVectorY + v * bottomVectorY
            )
    }

    companion object {
        /**
         * Reproduces Qualcomm's three-point affine crop: top-left, bottom-left and top-right
         * corners map to (0,0), (0,H-1) and (W-1,0), respectively.
         */
        fun fromPalmRegion(
            region: LiteRtPalmRegion,
            sourceWidthPx: Int,
            sourceHeightPx: Int,
            inputWidthPx: Int = 256,
            inputHeightPx: Int = 256
        ): QualcommHandRoiTransform {
            require(sourceWidthPx > 0 && sourceHeightPx > 0)
            require(inputWidthPx > 1 && inputHeightPx > 1)

            val centerX = region.centerX * sourceWidthPx
            val centerY = region.centerY * sourceHeightPx
            val halfWidth = region.width * sourceWidthPx * 0.5f
            val halfHeight = region.height * sourceHeightPx * 0.5f
            val cosTheta = cos(region.rotationRadians)
            val sinTheta = sin(region.rotationRadians)

            fun corner(xSign: Float, ySign: Float): Pair<Float, Float> {
                val localX = xSign * halfWidth
                val localY = ySign * halfHeight
                return (
                    centerX + cosTheta * localX - sinTheta * localY
                    ) to (
                    centerY + sinTheta * localX + cosTheta * localY
                    )
            }

            val topLeft = corner(-1f, -1f)
            val bottomLeft = corner(-1f, 1f)
            val topRight = corner(1f, -1f)
            return QualcommHandRoiTransform(
                sourceWidthPx = sourceWidthPx,
                sourceHeightPx = sourceHeightPx,
                inputWidthPx = inputWidthPx,
                inputHeightPx = inputHeightPx,
                topLeftX = topLeft.first,
                topLeftY = topLeft.second,
                bottomLeftX = bottomLeft.first,
                bottomLeftY = bottomLeft.second,
                topRightX = topRight.first,
                topRightY = topRight.second
            )
        }
    }
}

/**
 * Extracts Qualcomm's oriented square hand crop from a borrowed ARGB camera buffer.
 *
 * Sampling matches the inverse of the source-to-model affine used by Qualcomm's MediaPipe
 * app: each output pixel maps across the TL->TR and TL->BL ROI edges. Out-of-frame samples
 * use a zero (black) border, as OpenCV warpAffine does by default. Output is RGB float [0,1].
 */
object QualcommHandRoiPreprocessor {

    fun writeNhwcRgbFloat(
        sourceArgb: IntArray,
        transform: QualcommHandRoiTransform,
        destinationNhwc: FloatArray,
        sourceRotationDegrees: Int = 0
    ): Boolean = write(
        sourceArgb,
        transform,
        destinationNhwc,
        OutputLayout.NHWC,
        sourceRotationDegrees
    )

    fun writeNchwRgbFloat(
        sourceArgb: IntArray,
        transform: QualcommHandRoiTransform,
        destinationNchw: FloatArray,
        sourceRotationDegrees: Int = 0
    ): Boolean = write(
        sourceArgb,
        transform,
        destinationNchw,
        OutputLayout.NCHW,
        sourceRotationDegrees
    )

    private enum class OutputLayout { NHWC, NCHW }

    private fun write(
        sourceArgb: IntArray,
        transform: QualcommHandRoiTransform,
        destination: FloatArray,
        layout: OutputLayout,
        sourceRotationDegrees: Int
    ): Boolean {
        val rotation = normalizeRotation(sourceRotationDegrees) ?: return false
        val rawWidth = if (rotation == 90 || rotation == 270) {
            transform.sourceHeightPx
        } else {
            transform.sourceWidthPx
        }
        val rawHeight = if (rotation == 90 || rotation == 270) {
            transform.sourceWidthPx
        } else {
            transform.sourceHeightPx
        }
        val requiredSource = rawWidth.toLong() * rawHeight
        val pixelCount = transform.inputWidthPx.toLong() * transform.inputHeightPx
        if (pixelCount <= 0L || pixelCount > Int.MAX_VALUE / CHANNELS) return false
        val requiredDestination = pixelCount * CHANNELS
        if (requiredSource > sourceArgb.size.toLong() ||
            requiredDestination > destination.size.toLong()
        ) return false

        val planeSize = pixelCount.toInt()
        destination.fill(0f, 0, requiredDestination.toInt())

        for (outputY in 0 until transform.inputHeightPx) {
            for (outputX in 0 until transform.inputWidthPx) {
                val uprightX = transform.sourceXAt(outputX, outputY)
                val uprightY = transform.sourceYAt(outputX, outputY)
                val rawX = uprightToRawX(uprightX, uprightY, rawWidth, rotation)
                val rawY = uprightToRawY(uprightX, uprightY, rawHeight, rotation)
                val sampled = sampleBilinearBlackBorder(
                    sourceArgb, rawWidth, rawHeight, rawX, rawY
                )

                val red = (sampled ushr 16 and 0xff) / RGB_MAX
                val green = (sampled ushr 8 and 0xff) / RGB_MAX
                val blue = (sampled and 0xff) / RGB_MAX
                val pixelIndex = outputY * transform.inputWidthPx + outputX
                when (layout) {
                    OutputLayout.NHWC -> {
                        val offset = pixelIndex * CHANNELS
                        destination[offset] = red
                        destination[offset + 1] = green
                        destination[offset + 2] = blue
                    }
                    OutputLayout.NCHW -> {
                        destination[pixelIndex] = red
                        destination[planeSize + pixelIndex] = green
                        destination[2 * planeSize + pixelIndex] = blue
                    }
                }
            }
        }
        return true
    }

    private fun normalizeRotation(degrees: Int): Int? {
        val normalized = ((degrees % 360) + 360) % 360
        return normalized.takeIf { it == 0 || it == 90 || it == 180 || it == 270 }
    }

    private fun uprightToRawX(
        x: Float,
        y: Float,
        rawWidth: Int,
        rotation: Int
    ): Float = when (rotation) {
        0 -> x
        90 -> y
        180 -> rawWidth - 1f - x
        270 -> rawWidth - 1f - y
        else -> error("Unsupported source rotation: $rotation")
    }

    private fun uprightToRawY(
        x: Float,
        y: Float,
        rawHeight: Int,
        rotation: Int
    ): Float = when (rotation) {
        0 -> y
        90 -> rawHeight - 1f - x
        180 -> rawHeight - 1f - y
        270 -> x
        else -> error("Unsupported source rotation: $rotation")
    }

    /** Bilinear interpolation with each out-of-frame source pixel treated as opaque black. */
    private fun sampleBilinearBlackBorder(
        sourceArgb: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceX: Float,
        sourceY: Float
    ): Int {
        if (!sourceX.isFinite() || !sourceY.isFinite()) return 0
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val xWeight = sourceX - x0
        val yWeight = sourceY - y0

        val topLeft = pixelOrBlack(sourceArgb, sourceWidth, sourceHeight, x0, y0)
        val topRight = pixelOrBlack(sourceArgb, sourceWidth, sourceHeight, x1, y0)
        val bottomLeft = pixelOrBlack(sourceArgb, sourceWidth, sourceHeight, x0, y1)
        val bottomRight = pixelOrBlack(sourceArgb, sourceWidth, sourceHeight, x1, y1)

        val alpha = interpolate(
            topLeft ushr 24 and 0xff, topRight ushr 24 and 0xff,
            bottomLeft ushr 24 and 0xff, bottomRight ushr 24 and 0xff,
            xWeight, yWeight
        ).toInt().coerceIn(0, 255)
        val red = interpolate(
            topLeft ushr 16 and 0xff, topRight ushr 16 and 0xff,
            bottomLeft ushr 16 and 0xff, bottomRight ushr 16 and 0xff,
            xWeight, yWeight
        ).toInt().coerceIn(0, 255)
        val green = interpolate(
            topLeft ushr 8 and 0xff, topRight ushr 8 and 0xff,
            bottomLeft ushr 8 and 0xff, bottomRight ushr 8 and 0xff,
            xWeight, yWeight
        ).toInt().coerceIn(0, 255)
        val blue = interpolate(
            topLeft and 0xff, topRight and 0xff,
            bottomLeft and 0xff, bottomRight and 0xff,
            xWeight, yWeight
        ).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun pixelOrBlack(
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Int = if (x in 0 until width && y in 0 until height) {
        sourceArgb[y * width + x]
    } else {
        0
    }

    private fun interpolate(
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        xWeight: Float,
        yWeight: Float
    ): Float {
        val top = topLeft + (topRight - topLeft) * xWeight
        val bottom = bottomLeft + (bottomRight - bottomLeft) * xWeight
        return top + (bottom - top) * yWeight
    }

    private const val CHANNELS = 3
    private const val RGB_MAX = 255f
}
