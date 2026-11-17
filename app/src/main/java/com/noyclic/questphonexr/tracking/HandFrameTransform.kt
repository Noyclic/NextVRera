package com.noyclic.questphonexr.tracking

/**
 * Maps normalized MediaPipe model coordinates back to the normalized coordinate
 * system of the rotated camera frame used to build the model bitmap.
 *
 * Camera rotation is already applied while drawing the model input. Keep landmarks
 * in that display-aligned frame: the sandbox's camera-to-world sensor matrix uses
 * the same display orientation. Only undo letterboxing and optional front mirroring.
 */
data class HandFrameTransform(
    val leftPx: Float,
    val topPx: Float,
    val scale: Float,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val rotatedWidthPx: Int,
    val rotatedHeightPx: Int,
    val mirroredHorizontally: Boolean = false
) {
    init {
        require(scale > 0f)
        require(targetWidthPx > 0 && targetHeightPx > 0)
        require(rotatedWidthPx > 0 && rotatedHeightPx > 0)
    }

    fun toRotatedFrameNormalized(x: Float, y: Float): Pair<Float, Float> {
        val rotatedX = ((x * targetWidthPx - leftPx) / scale)
            .coerceIn(0f, rotatedWidthPx.toFloat()) / rotatedWidthPx
        val rotatedY = ((y * targetHeightPx - topPx) / scale)
            .coerceIn(0f, rotatedHeightPx.toFloat()) / rotatedHeightPx

        val unmirroredX = if (mirroredHorizontally) 1f - rotatedX else rotatedX
        return unmirroredX.coerceIn(0f, 1f) to rotatedY.coerceIn(0f, 1f)
    }
}

/**
 * Normalize an external image rotation to a supported right-angle transform.
 *
 * Unlike [normalizeCameraRotationDegrees], this strict helper rejects values such as 45°
 * instead of silently treating them as unrotated. Top-down ROI crops require an exact
 * source-to-camera mapping.
 */
internal fun normalizeRightAngleRotation(rotationDegrees: Int): Int? {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return normalized.takeIf { it == 0 || it == 90 || it == 180 || it == 270 }
}

/** Normalize CameraX rotation to the right-angle transforms supported by the bitmap pipeline. */
internal fun normalizeCameraRotationDegrees(rotationDegrees: Int): Int {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized % 90 == 0) normalized else 0
}
