package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

/** Shared power-grip interpretation for Sandbox and the EndXR input bridge. */
internal object HandGestureEstimator {
    private val fingerMcpIndices = intArrayOf(5, 9, 13, 17)

    fun isPowerGrip(hand: TrackedHand): Boolean {
        val worldCount = curledFingerCount(hand.worldPoints)
        val curledFingers = worldCount ?: curledFingerCount(hand.points) ?: return false
        return curledFingers >= MIN_POWER_GRIP_FINGERS
    }

    /**
     * Continuous 0..1 finger-closure estimate for XR_EXT_hand_interaction grasp_ext/value.
     * Each valid finger contributes its MCP-to-tip fold ratio normalized by palm length,
     * so the value is largely independent of hand size and camera distance.
     */
    fun graspStrength(hand: TrackedHand): Float {
        val foldRatios = fingerFoldRatios(hand.worldPoints)
            ?: fingerFoldRatios(hand.points)
            ?: return 0f
        return foldRatios.map { ratio ->
            ((OPEN_FINGER_FOLD_RATIO - ratio) /
                (OPEN_FINGER_FOLD_RATIO - CLOSED_FINGER_FOLD_RATIO)).coerceIn(0f, 1f)
        }.average().toFloat().coerceIn(0f, 1f)
    }

    /**
     * Runtime-defined aim activation gesture: index finger extended while the
     * middle/ring/little fingers remain curled. This is deliberately separate
     * from pinch so pointing at a distant target does not reuse trigger pressure.
     */
    fun aimActivationStrength(hand: TrackedHand): Float {
        val source = hand.rawWorldPoints.takeIf { it.size >= LANDMARK_COUNT }
            ?: hand.worldPoints.takeIf { it.size >= LANDMARK_COUNT }
            ?: hand.rawPoints.takeIf { it.size >= LANDMARK_COUNT }
            ?: hand.points
        val indexExtension = fingerFoldRatio(source, 5) ?: return 0f
        val otherCurled = intArrayOf(9, 13, 17).mapNotNull { mcpIndex ->
            fingerFoldRatio(source, mcpIndex)
        }
        if (otherCurled.size < MIN_AIM_SUPPORT_FINGERS) return 0f
        val indexExtended = (
            (indexExtension - AIM_INDEX_FOLD_MIN_RATIO) /
                (AIM_INDEX_FOLD_MAX_RATIO - AIM_INDEX_FOLD_MIN_RATIO)
            ).coerceIn(0f, 1f)
        val remainingFingersCurled = otherCurled.map { ratio ->
            ((OPEN_FINGER_FOLD_RATIO - ratio) /
                (OPEN_FINGER_FOLD_RATIO - CLOSED_FINGER_FOLD_RATIO)).coerceIn(0f, 1f)
        }.average().toFloat()
        return (indexExtended * remainingFingersCurled).coerceIn(0f, 1f)
    }

    internal fun curledFingerCount(points: List<HandPoint>): Int? =
        fingerFoldRatios(points)?.count { it < POWER_GRIP_FOLD_RATIO }

    private fun fingerFoldRatio(points: List<HandPoint>, mcpIndex: Int): Float? {
        if (points.size < LANDMARK_COUNT || mcpIndex !in fingerMcpIndices) return null
        val wrist = points[0]
        val mcp = points[mcpIndex]
        val pip = points[mcpIndex + 1]
        val dip = points[mcpIndex + 2]
        val tip = points[mcpIndex + 3]
        val palmLength = distance(wrist, mcp)
        if (!palmLength.isFinite() || palmLength < MIN_PALM_FINGER_LENGTH) return null
        val segments = floatArrayOf(
            distance(mcp, pip), distance(pip, dip), distance(dip, tip)
        )
        if (segments.any { !it.isFinite() || it / palmLength < MIN_FINGER_SEGMENT_RATIO }) {
            return null
        }
        val foldedLength = distance(mcp, tip)
        return foldedLength.takeIf { it.isFinite() }?.div(palmLength)
    }

    private fun fingerFoldRatios(points: List<HandPoint>): List<Float>? {
        if (points.size < LANDMARK_COUNT) return null
        val wrist = points[0]
        val ratios = mutableListOf<Float>()
        for (mcpIndex in fingerMcpIndices) {
            val mcp = points[mcpIndex]
            val pip = points[mcpIndex + 1]
            val dip = points[mcpIndex + 2]
            val tip = points[mcpIndex + 3]
            val palmLength = distance(wrist, mcp)
            if (!palmLength.isFinite() || palmLength < MIN_PALM_FINGER_LENGTH) continue
            val segments = floatArrayOf(
                distance(mcp, pip), distance(pip, dip), distance(dip, tip)
            )
            if (segments.any { !it.isFinite() || it / palmLength < MIN_FINGER_SEGMENT_RATIO }) {
                continue
            }
            val foldedLength = distance(mcp, tip)
            if (foldedLength.isFinite()) ratios += foldedLength / palmLength
        }
        return ratios.takeIf { it.size >= MIN_POWER_GRIP_FINGERS }
    }

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private const val LANDMARK_COUNT = 21
    private const val MIN_PALM_FINGER_LENGTH = 0.025f
    private const val MIN_FINGER_SEGMENT_RATIO = 0.055f
    private const val POWER_GRIP_FOLD_RATIO = 0.92f
    private const val OPEN_FINGER_FOLD_RATIO = 1.60f
    private const val CLOSED_FINGER_FOLD_RATIO = 0.65f
    private const val MIN_POWER_GRIP_FINGERS = 3
    private const val MIN_AIM_SUPPORT_FINGERS = 2
    private const val AIM_INDEX_FOLD_MIN_RATIO = 1.00f
    private const val AIM_INDEX_FOLD_MAX_RATIO = 1.65f
}
