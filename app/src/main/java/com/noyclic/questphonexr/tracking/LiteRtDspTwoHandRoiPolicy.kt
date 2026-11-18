package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

/**
 * Geometry helpers for the isolated two-ROI RTMPose diagnostic.
 *
 * RTMPose-Hand is top-down: these crops do not constitute full-frame hand detection.
 * Each seed owns one half of the upright camera frame until landmarks can refine it.
 */
internal object LiteRtDspTwoHandRoiPolicy {
    private const val MIN_CROP_PX = 64
    private const val MIN_FINGER_REACH = 0.025f
    private const val MIN_LANDMARK_SPAN = 0.025f
    private const val MIN_WRIST_SEPARATION = 0.07f
    private val TIP_INDICES = intArrayOf(4, 8, 12, 16, 20)

    fun createSeedCrops(sourceWidthPx: Int, sourceHeightPx: Int): List<HandRoiCrop> {
        if (sourceWidthPx < MIN_CROP_PX * 2 || sourceHeightPx < MIN_CROP_PX) return emptyList()
        val halfWidth = sourceWidthPx / 2
        val size = (minOf(halfWidth, sourceHeightPx) * 0.90f)
            .toInt()
            .coerceAtLeast(MIN_CROP_PX)
            .coerceAtMost(minOf(halfWidth, sourceHeightPx))
        if (size < MIN_CROP_PX) return emptyList()

        fun cropAt(centerX: Float): HandRoiCrop {
            val left = (centerX - size * 0.5f).toInt().coerceIn(0, sourceWidthPx - size)
            val top = ((sourceHeightPx - size) * 0.5f).toInt().coerceIn(0, sourceHeightPx - size)
            return HandRoiCrop(left, top, size, sourceWidthPx, sourceHeightPx)
        }

        return listOf(cropAt(sourceWidthPx * 0.25f), cropAt(sourceWidthPx * 0.75f))
    }

    /**
     * The model has no presence score. Reject collapsed/degenerate argmax layouts before
     * publishing them; this is a conservative geometry heuristic, not a detector confidence.
     */
    fun hasPlausibleHand(points: List<HandPoint>, crop: HandRoiCrop): Boolean {
        if (points.size != HandRoiCrop.REQUIRED_HAND_LANDMARKS) return false
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) {
            return false
        }

        val left = crop.leftPx.toFloat() / crop.sourceWidthPx
        val top = crop.topPx.toFloat() / crop.sourceHeightPx
        val right = (crop.leftPx + crop.sizePx).toFloat() / crop.sourceWidthPx
        val bottom = (crop.topPx + crop.sizePx).toFloat() / crop.sourceHeightPx
        val toleranceX = 3f / crop.sourceWidthPx
        val toleranceY = 3f / crop.sourceHeightPx
        if (points.any {
                it.x < left - toleranceX || it.x > right + toleranceX ||
                    it.y < top - toleranceY || it.y > bottom + toleranceY
            }
        ) return false

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        if (maxOf(maxX - minX, maxY - minY) < MIN_LANDMARK_SPAN) return false

        val wrist = points[0]
        val extendedTips = TIP_INDICES.count { index ->
            val tip = points[index]
            val dx = tip.x - wrist.x
            val dy = tip.y - wrist.y
            sqrt(dx * dx + dy * dy) >= MIN_FINGER_REACH
        }
        return extendedTips >= 3
    }

    fun areDistinctHands(
        first: List<HandPoint>,
        second: List<HandPoint>
    ): Boolean {
        if (first.size != HandRoiCrop.REQUIRED_HAND_LANDMARKS ||
            second.size != HandRoiCrop.REQUIRED_HAND_LANDMARKS
        ) return false
        val dx = first[0].x - second[0].x
        val dy = first[0].y - second[0].y
        return sqrt(dx * dx + dy * dy) >= MIN_WRIST_SEPARATION
    }
}
