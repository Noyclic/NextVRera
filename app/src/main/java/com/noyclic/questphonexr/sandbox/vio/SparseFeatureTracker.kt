package com.noyclic.questphonexr.sandbox.vio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Compact grayscale frame; pixels are row-major unsigned 8-bit values. */
data class GrayFrame(
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    fun luminance(x: Int, y: Int) = pixels[y * width + x].toInt() and 0xff
}

data class SparseFeature(
    val x: Int,
    val y: Int,
    val response: Int,
    val descriptor: Long
)

data class FeatureMatch(
    val previous: SparseFeature,
    val current: SparseFeature,
    val hammingDistance: Int,
    val displacement: Vec2
)

data class VisualMotionEstimate(
    val timestampNanos: Long,
    val matches: Int,
    val inliers: Int,
    val displacementPixels: Vec2,
    val displacementNormalized: Vec2,
    val medianResidualPixels: Double,
    val confidence: Double,
    val valid: Boolean,
    val reason: String
)

data class FeatureTrackerConfig(
    val maximumFeatures: Int = 180,
    val gridColumns: Int = 8,
    val gridRows: Int = 6,
    val cornerThreshold: Int = 22,
    val minimumDistancePixels: Int = 7,
    val descriptorRadius: Int = 4,
    val maximumHammingDistance: Int = 22,
    val ratioThreshold: Double = 0.82,
    val maximumSearchRadius: Int = 20,
    val minimumMatches: Int = 8,
    val inlierThresholdPixels: Double = 2.5
) {
    init {
        require(maximumFeatures > 0)
        require(gridColumns > 0 && gridRows > 0)
        require(cornerThreshold in 1..255)
        require(minimumDistancePixels >= 1)
        require(descriptorRadius >= 2)
        require(maximumHammingDistance in 1..64)
        require(ratioThreshold in 0.1..1.0)
        require(maximumSearchRadius >= 1)
        require(minimumMatches >= 3)
        require(inlierThresholdPixels > 0.0)
    }
}

/**
 * CPU-light sparse tracker:
 *  - FAST-like 16-point circle corner response;
 *  - grid quotas prevent all features clustering in one textured corner;
 *  - deterministic 64-bit BRIEF descriptor;
 *  - local spatial search + Hamming ratio test;
 *  - median displacement and MAD-based inlier rejection.
 *
 * This estimates 2D image motion only. It does not infer depth, metric translation, or 6DoF.
 */
class SparseFeatureTracker(private val config: FeatureTrackerConfig = FeatureTrackerConfig()) {
    private val circle = arrayOf(
        -3 to 0, -3 to 1, -2 to 2, -1 to 3, 0 to 3, 1 to 3, 2 to 2, 3 to 1,
        3 to 0, 3 to -1, 2 to -2, 1 to -3, 0 to -3, -1 to -3, -2 to -2, -3 to -1
    )
    private val descriptorPairs = createDescriptorPairs(config.descriptorRadius)
    private var previousFrame: GrayFrame? = null
    private var previousFeatures: List<SparseFeature> = emptyList()

    @Synchronized
    fun reset() {
        previousFrame = null
        previousFeatures = emptyList()
    }

    @Synchronized
    fun process(frame: GrayFrame): VisualMotionEstimate {
        if (frame.timestampNanos <= 0L) {
            return invalid(frame.timestampNanos, "invalid frame timestamp")
        }
        val oldFrame = previousFrame
        val oldFeatures = previousFeatures
        val currentFeatures = detect(frame)
        previousFrame = frame
        previousFeatures = currentFeatures
        if (oldFrame == null) return invalid(frame.timestampNanos, "waiting for second frame")
        if (oldFrame.width != frame.width || oldFrame.height != frame.height) {
            return invalid(frame.timestampNanos, "frame dimensions changed; tracker reset")
        }
        if (frame.timestampNanos <= oldFrame.timestampNanos) {
            return invalid(frame.timestampNanos, "non-monotonic frame timestamp")
        }
        val matches = match(oldFeatures, currentFeatures)
        if (matches.size < config.minimumMatches) {
            return VisualMotionEstimate(
                frame.timestampNanos, matches.size, 0, Vec2(0.0, 0.0), Vec2(0.0, 0.0),
                Double.POSITIVE_INFINITY, 0.0, false, "not enough stable feature matches"
            )
        }
        return robustMotion(frame, matches)
    }

    @Synchronized
    fun detect(frame: GrayFrame): List<SparseFeature> {
        val margin = max(4, config.descriptorRadius + 1)
        if (frame.width <= margin * 2 || frame.height <= margin * 2) return emptyList()
        val candidates = ArrayList<SparseFeature>(config.maximumFeatures * 2)
        val cellWidth = frame.width.toDouble() / config.gridColumns
        val cellHeight = frame.height.toDouble() / config.gridRows
        val cellQuota = max(1, (config.maximumFeatures + config.gridColumns * config.gridRows - 1) /
            (config.gridColumns * config.gridRows))
        val byCell = Array(config.gridColumns * config.gridRows) { ArrayList<SparseFeature>() }

        var y = margin
        while (y < frame.height - margin) {
            var x = margin
            while (x < frame.width - margin) {
                val response = cornerResponse(frame, x, y)
                if (response >= config.cornerThreshold) {
                    val descriptor = describe(frame, x, y)
                    val feature = SparseFeature(x, y, response, descriptor)
                    val column = min(config.gridColumns - 1, (x / cellWidth).toInt())
                    val row = min(config.gridRows - 1, (y / cellHeight).toInt())
                    val bucket = byCell[row * config.gridColumns + column]
                    if (bucket.size < cellQuota) insertBest(bucket, feature, cellQuota)
                }
                x += 2
            }
            y += 2
        }
        byCell.forEach(candidates::addAll)
        candidates.sortByDescending { it.response }
        val selected = ArrayList<SparseFeature>(config.maximumFeatures)
        for (candidate in candidates) {
            if (selected.size >= config.maximumFeatures) break
            if (selected.none {
                    val dx = it.x - candidate.x
                    val dy = it.y - candidate.y
                    dx * dx + dy * dy < config.minimumDistancePixels * config.minimumDistancePixels
                }) selected += candidate
        }
        return selected
    }

    private fun insertBest(bucket: MutableList<SparseFeature>, feature: SparseFeature, quota: Int) {
        if (bucket.size < quota) {
            bucket += feature
            return
        }
        val weakest = bucket.indices.minByOrNull { bucket[it].response } ?: return
        if (feature.response > bucket[weakest].response) bucket[weakest] = feature
    }

    private fun cornerResponse(frame: GrayFrame, x: Int, y: Int): Int {
        val center = frame.luminance(x, y)
        var best = 0
        var brightRun = 0
        var darkRun = 0
        for (index in 0 until 32) {
            val (dx, dy) = circle[index % 16]
            val difference = frame.luminance(x + dx, y + dy) - center
            if (difference > config.cornerThreshold) {
                brightRun++
                darkRun = 0
            } else if (difference < -config.cornerThreshold) {
                darkRun++
                brightRun = 0
            } else {
                brightRun = 0
                darkRun = 0
            }
            best = max(best, max(brightRun, darkRun))
        }
        if (best < 9) return 0
        var score = 0
        circle.forEach { (dx, dy) ->
            score += abs(frame.luminance(x + dx, y + dy) - center)
        }
        return score / circle.size
    }

    private fun describe(frame: GrayFrame, x: Int, y: Int): Long {
        var bits = 0L
        descriptorPairs.forEachIndexed { index, pair ->
            val first = frame.luminance(x + pair.first.first, y + pair.first.second)
            val second = frame.luminance(x + pair.second.first, y + pair.second.second)
            if (first < second) bits = bits or (1L shl index)
        }
        return bits
    }

    private fun match(
        previous: List<SparseFeature>,
        current: List<SparseFeature>
    ): List<FeatureMatch> {
        if (previous.isEmpty() || current.isEmpty()) return emptyList()
        val result = ArrayList<FeatureMatch>()
        val usedCurrent = HashSet<Int>()
        previous.forEach { old ->
            var bestIndex = -1
            var bestDistance = Int.MAX_VALUE
            var secondDistance = Int.MAX_VALUE
            current.forEachIndexed { index, candidate ->
                if (abs(candidate.x - old.x) > config.maximumSearchRadius ||
                    abs(candidate.y - old.y) > config.maximumSearchRadius ||
                    index in usedCurrent
                ) return@forEachIndexed
                val distance = java.lang.Long.bitCount(old.descriptor xor candidate.descriptor)
                if (distance < bestDistance) {
                    secondDistance = bestDistance
                    bestDistance = distance
                    bestIndex = index
                } else if (distance < secondDistance) {
                    secondDistance = distance
                }
            }
            if (bestIndex >= 0 && bestDistance <= config.maximumHammingDistance &&
                (secondDistance == Int.MAX_VALUE || bestDistance < secondDistance * config.ratioThreshold)
            ) {
                val now = current[bestIndex]
                usedCurrent += bestIndex
                result += FeatureMatch(
                    old, now, bestDistance,
                    Vec2((now.x - old.x).toDouble(), (now.y - old.y).toDouble())
                )
            }
        }
        return result
    }

    private fun robustMotion(frame: GrayFrame, matches: List<FeatureMatch>): VisualMotionEstimate {
        val dx = matches.map { it.displacement.x }.median()
        val dy = matches.map { it.displacement.y }.median()
        val residuals = matches.map {
            val rx = it.displacement.x - dx
            val ry = it.displacement.y - dy
            sqrt(rx * rx + ry * ry)
        }
        val medianResidual = residuals.median()
        val inlierLimit = max(config.inlierThresholdPixels, medianResidual * 2.5 + 0.5)
        val inliers = matches.indices.filter { residuals[it] <= inlierLimit }
        val inlierRatio = inliers.size.toDouble() / matches.size
        val meanDescriptorQuality = matches.map {
            1.0 - it.hammingDistance.toDouble() / 64.0
        }.average().coerceIn(0.0, 1.0)
        val confidence = (inlierRatio * meanDescriptorQuality *
            (inliers.size.toDouble() / config.minimumMatches).coerceAtMost(1.0))
            .coerceIn(0.0, 1.0)
        val valid = inliers.size >= config.minimumMatches && confidence >= 0.12
        val robustDx = if (valid) inliers.map { matches[it].displacement.x }.median() else 0.0
        val robustDy = if (valid) inliers.map { matches[it].displacement.y }.median() else 0.0
        return VisualMotionEstimate(
            timestampNanos = frame.timestampNanos,
            matches = matches.size,
            inliers = inliers.size,
            displacementPixels = Vec2(robustDx, robustDy),
            displacementNormalized = Vec2(robustDx / frame.width, robustDy / frame.height),
            medianResidualPixels = medianResidual,
            confidence = if (valid) confidence else confidence * 0.25,
            valid = valid,
            reason = if (valid) "sparse feature flow; scale unknown" else "visual consensus rejected"
        )
    }

    private fun invalid(timestamp: Long, reason: String) = VisualMotionEstimate(
        timestamp, 0, 0, Vec2(0.0, 0.0), Vec2(0.0, 0.0),
        Double.POSITIVE_INFINITY, 0.0, false, reason
    )

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5
        else sorted[middle]
    }

    private fun createDescriptorPairs(radius: Int): List<Pair<Pair<Int, Int>, Pair<Int, Int>>> {
        // Fixed, spatially distributed pattern avoids runtime RNG and is stable across app versions.
        val points = arrayOf(
            -4 to -3, -2 to 1, 3 to -2, 1 to 4, -1 to -4, 4 to 2, -3 to 3, 2 to -1,
            -4 to 1, 0 to -3, 3 to 3, -2 to -4, 1 to 2, 4 to -1, -3 to -1, 2 to 4,
            -1 to 1, 3 to 0, -4 to -2, 0 to 4, 2 to 2, -2 to 0, 4 to 3, -3 to 2,
            1 to -2, -4 to 4, 3 to -4, -1 to 3, 2 to -3, -3 to 0, 4 to 1, 0 to 0,
            -2 to 3, 1 to 1, 3 to 2, -4 to -4, 2 to 1, -1 to -1, 4 to -3, -3 to 4,
            0 to 2, -2 to -2, 1 to 3, -4 to 2, 3 to -1, -1 to -3, 2 to 0, -3 to -3,
            4 to 4, -2 to 2, 0 to -1, 3 to 1, -4 to 3, 1 to -4, -1 to 4, 2 to 3,
            -3 to 1, 4 to 0, -2 to -3, 0 to 3, 3 to 4, -4 to 0, 1 to 0, -1 to 2
        )
        return points.take(64).mapIndexed { index, point ->
            val other = points[(index * 17 + 23) % points.size]
            val scale = radius / 4.0
            val first = (point.first * scale).toInt() to (point.second * scale).toInt()
            val second = (other.first * scale).toInt() to (other.second * scale).toInt()
            first to second
        }
    }
}
