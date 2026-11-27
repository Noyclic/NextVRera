package com.noyclic.questphonexr.tracking

import kotlin.math.floor

/**
 * Qualcomm MediaPipe-Hand full-frame detector input preprocessor.
 *
 * Matches the published recipe's RGB float [0,1] input, bilinear resize and centered
 * zero-padding. [LiteRtPalmInputTransform] must be shared with the output decoder so that
 * decoded palm coordinates are mapped back through the exact integer resize dimensions.
 *
 * The camera frame is not mirrored here. The optional right-angle rotation converts a raw
 * CameraX pixel buffer into the upright source coordinate system described by the transform.
 * Both layouts write into caller-owned reusable buffers and allocate no per-pixel objects.
 */
object QualcommPalmInputPreprocessor {

    fun writeNhwcRgbFloat(
        sourceArgb: IntArray,
        transform: LiteRtPalmInputTransform,
        destinationNhwc: FloatArray,
        sourceRotationDegrees: Int = 0
    ): Boolean = write(
        sourceArgb = sourceArgb,
        transform = transform,
        destination = destinationNhwc,
        layout = OutputLayout.NHWC,
        sourceRotationDegrees = sourceRotationDegrees
    )

    fun writeNchwRgbFloat(
        sourceArgb: IntArray,
        transform: LiteRtPalmInputTransform,
        destinationNchw: FloatArray,
        sourceRotationDegrees: Int = 0
    ): Boolean = write(
        sourceArgb = sourceArgb,
        transform = transform,
        destination = destinationNchw,
        layout = OutputLayout.NCHW,
        sourceRotationDegrees = sourceRotationDegrees
    )

    private enum class OutputLayout { NHWC, NCHW }

    private fun write(
        sourceArgb: IntArray,
        transform: LiteRtPalmInputTransform,
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
        val requiredDestination = pixelCount * CHANNELS
        if (requiredSource > sourceArgb.size.toLong() ||
            requiredDestination > destination.size.toLong() ||
            requiredDestination > Int.MAX_VALUE
        ) return false

        val planeSize = pixelCount.toInt()
        val requiredValues = requiredDestination.toInt()
        // Reused buffers may contain data from the previous frame. The letterbox is black.
        destination.fill(0f, 0, requiredValues)

        val sourcePerOutputX = transform.sourceWidthPx.toFloat() /
            transform.contentWidthPx.toFloat()
        val sourcePerOutputY = transform.sourceHeightPx.toFloat() /
            transform.contentHeightPx.toFloat()
        val cropRight = transform.sourceWidthPx - 1
        val cropBottom = transform.sourceHeightPx - 1

        for (contentY in 0 until transform.contentHeightPx) {
            // Same pixel-center convention as bilinear resize with align_corners=false.
            val sourceY = (
                (contentY + 0.5f) * sourcePerOutputY - 0.5f
            ).coerceIn(0f, cropBottom.toFloat())
            val y0 = floor(sourceY).toInt().coerceIn(0, cropBottom)
            val y1 = (y0 + 1).coerceAtMost(cropBottom)
            val yWeight = (sourceY - y0).coerceIn(0f, 1f)

            for (contentX in 0 until transform.contentWidthPx) {
                val sourceX = (
                    (contentX + 0.5f) * sourcePerOutputX - 0.5f
                ).coerceIn(0f, cropRight.toFloat())
                val x0 = floor(sourceX).toInt().coerceIn(0, cropRight)
                val x1 = (x0 + 1).coerceAtMost(cropRight)
                val xWeight = (sourceX - x0).coerceIn(0f, 1f)

                val topLeft = argbAtRotated(
                    sourceArgb, rawWidth, rawHeight, x0, y0, rotation
                )
                val topRight = argbAtRotated(
                    sourceArgb, rawWidth, rawHeight, x1, y0, rotation
                )
                val bottomLeft = argbAtRotated(
                    sourceArgb, rawWidth, rawHeight, x0, y1, rotation
                )
                val bottomRight = argbAtRotated(
                    sourceArgb, rawWidth, rawHeight, x1, y1, rotation
                )

                val red = interpolate(
                    topLeft ushr 16 and 0xff,
                    topRight ushr 16 and 0xff,
                    bottomLeft ushr 16 and 0xff,
                    bottomRight ushr 16 and 0xff,
                    xWeight,
                    yWeight
                ) / RGB_MAX
                val green = interpolate(
                    topLeft ushr 8 and 0xff,
                    topRight ushr 8 and 0xff,
                    bottomLeft ushr 8 and 0xff,
                    bottomRight ushr 8 and 0xff,
                    xWeight,
                    yWeight
                ) / RGB_MAX
                val blue = interpolate(
                    topLeft and 0xff,
                    topRight and 0xff,
                    bottomLeft and 0xff,
                    bottomRight and 0xff,
                    xWeight,
                    yWeight
                ) / RGB_MAX

                val outputX = transform.padLeftPx + contentX
                val outputY = transform.padTopPx + contentY
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

    private fun argbAtRotated(
        sourceArgb: IntArray,
        rawWidth: Int,
        rawHeight: Int,
        rotatedX: Int,
        rotatedY: Int,
        rotation: Int
    ): Int {
        val rawX: Int
        val rawY: Int
        when (rotation) {
            0 -> {
                rawX = rotatedX
                rawY = rotatedY
            }
            90 -> {
                rawX = rotatedY
                rawY = rawHeight - 1 - rotatedX
            }
            180 -> {
                rawX = rawWidth - 1 - rotatedX
                rawY = rawHeight - 1 - rotatedY
            }
            270 -> {
                rawX = rawWidth - 1 - rotatedY
                rawY = rotatedX
            }
            else -> error("Unsupported source rotation: $rotation")
        }
        return sourceArgb[rawY * rawWidth + rawX]
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
