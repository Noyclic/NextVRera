package com.noyclic.questphonexr.tracking

/**
 * Borrowed CameraX image frame passed synchronously to standalone LiteRT model adapters.
 * The pixel array must remain valid until [LiteRtStandaloneTracker.process] returns.
 */
data class LiteRtCameraFrame(
    val timestampMs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val argbPixels: IntArray
) {
    init {
        require(timestampMs > 0L)
        require(widthPx > 0 && heightPx > 0)
        require(argbPixels.size.toLong() >= widthPx.toLong() * heightPx.toLong())
    }
}

/** Normalized full-frame palm ROI; rotation is clockwise radians in image coordinates. */
data class LiteRtPalmRegion(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationRadians: Float = 0f,
    val confidence: Float = 0.5f,
    val sideHint: HandSide = HandSide.UNKNOWN
) {
    init {
        require(centerX.isFinite() && centerY.isFinite())
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
        require(rotationRadians.isFinite())
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class LiteRtPalmDetectionBatch(
    val regions: List<LiteRtPalmRegion>,
    val preprocessingLatencyMs: Long = 0L,
    val inferenceLatencyMs: Long = 0L
)

data class LiteRtHandLandmarkPrediction(
    /** Exactly 21 points, already mapped back into full-frame normalized coordinates. */
    val points: List<HandPoint>,
    val presenceScore: Float,
    val side: HandSide = HandSide.UNKNOWN,
    /** Optional hand-local metric landmarks. Empty when the model does not produce them. */
    val worldPoints: List<HandPoint> = emptyList(),
    val preprocessingLatencyMs: Long = 0L,
    val inferenceLatencyMs: Long = 0L
)

/** Full-frame palm detector; the implementation must use LiteRT, not MediaPipe Tasks. */
fun interface LiteRtPalmDetector {
    fun detect(frame: LiteRtCameraFrame): LiteRtPalmDetectionBatch
}

/** Per-ROI landmark model; the implementation must use LiteRT, not MediaPipe Tasks. */
fun interface LiteRtHandLandmarkEstimator {
    fun estimate(
        frame: LiteRtCameraFrame,
        region: LiteRtPalmRegion
    ): LiteRtHandLandmarkPrediction?
}

/**
 * Independent LiteRT hand-tracking orchestration.
 *
 * It owns palm reacquisition, ROI continuity, bounded missed-frame handling, hand identity,
 * pinch state and conversion to the common EndXR frame. It has no MediaPipe dependency and
 * never accepts a MediaPipe observation as a fallback.
 *
 * Concrete LiteRT model adapters are injected so palm detection and landmark inference can
 * choose their own accelerator (QNN/GPU/CPU) and be benchmarked independently.
 */
class LiteRtStandaloneTracker(
    private val palmDetector: LiteRtPalmDetector,
    private val landmarkEstimator: LiteRtHandLandmarkEstimator,
    private val maxHands: Int = DEFAULT_MAX_HANDS,
    private val detectorIntervalFrames: Int = DEFAULT_DETECTOR_INTERVAL_FRAMES,
    private val maxConsecutiveMisses: Int = DEFAULT_MAX_CONSECUTIVE_MISSES,
    private val minimumPalmConfidence: Float = DEFAULT_MIN_PALM_CONFIDENCE,
    private val minimumPresenceScore: Float = DEFAULT_MIN_PRESENCE_SCORE
) {
    private data class Track(
        val id: Long,
        var region: LiteRtPalmRegion,
        var missedFrames: Int = 0
    )

    private val tracks = LinkedHashMap<Long, Track>()
    private val pinchTracker = HandPinchTracker()
    private var nextTrackId = 1L
    private var framesSinceDetection = detectorIntervalFrames
    private var lastFrameTimestampMs = 0L

    init {
        require(maxHands in 1..2)
        require(detectorIntervalFrames in 1..60)
        require(maxConsecutiveMisses in 1..5)
        require(minimumPalmConfidence.isFinite() && minimumPalmConfidence in 0f..1f)
        require(minimumPresenceScore.isFinite() && minimumPresenceScore in 0f..1f)
    }

    val activeTrackCount: Int
        get() = tracks.size

    /**
     * Processes one camera frame synchronously. Model adapters must not retain the borrowed
     * pixel array after returning. Timestamps must be strictly increasing.
     */
    fun process(frame: LiteRtCameraFrame): HandTrackingFrame {
        require(frame.timestampMs > lastFrameTimestampMs) {
            "LiteRT camera frame timestamps must be strictly increasing."
        }
        lastFrameTimestampMs = frame.timestampMs
        val startedAtNanos = System.nanoTime()
        var preprocessingLatencyMs = 0L
        var detectorInferenceLatencyMs = 0L

        val shouldDetect = tracks.isEmpty() || framesSinceDetection >= detectorIntervalFrames
        if (shouldDetect) {
            val batch = palmDetector.detect(frame)
            preprocessingLatencyMs += batch.preprocessingLatencyMs.coerceAtLeast(0L)
            detectorInferenceLatencyMs += batch.inferenceLatencyMs.coerceAtLeast(0L)
            mergeDetections(batch.regions)
            framesSinceDetection = 0
        } else {
            framesSinceDetection += 1
        }

        val observedHands = ArrayList<TrackedHand>(tracks.size)
        val lostTrackIds = ArrayList<Long>()
        var landmarkInferenceLatencyMs = 0L

        tracks.values.forEach { track ->
            val prediction = landmarkEstimator.estimate(frame, track.region)
            if (prediction == null ||
                !prediction.presenceScore.isFinite() ||
                prediction.presenceScore < minimumPresenceScore ||
                !hasValidLandmarks(prediction)
            ) {
                track.missedFrames += 1
                if (track.missedFrames >= maxConsecutiveMisses) lostTrackIds += track.id
                return@forEach
            }

            track.missedFrames = 0
            preprocessingLatencyMs += prediction.preprocessingLatencyMs.coerceAtLeast(0L)
            landmarkInferenceLatencyMs += prediction.inferenceLatencyMs.coerceAtLeast(0L)
            track.region = updateRegion(track.region, prediction.points)

            val worldPoints = prediction.worldPoints.takeIf {
                it.size == LANDMARK_COUNT && it.all(::isFinite)
            }.orEmpty()
            observedHands += TrackedHand(
                points = prediction.points,
                side = prediction.side.takeUnless { it == HandSide.UNKNOWN }
                    ?: track.region.sideHint,
                worldPoints = worldPoints,
                trackingId = track.id,
                rawPoints = prediction.points,
                rawWorldPoints = worldPoints,
                rawTimestampMs = frame.timestampMs
            )
        }

        lostTrackIds.forEach { tracks.remove(it) }
        val pinchSignals = pinchTracker.updateFrame(
            observedHands.map { HandPinchSample(it.side, it.points) },
            frame.timestampMs
        )
        val handsWithPinch = observedHands.mapIndexed { index, hand ->
            val signal = pinchSignals[index]
            hand.copy(
                pinchStrength = signal.strength,
                isPinching = signal.isPinching,
                pinchDistanceRatio = signal.normalizedDistanceRatio,
                pinchUsesWorldLandmarks = signal.usedWorldLandmarks
            )
        }

        val totalLatencyMs = (
            (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
        ).coerceAtLeast(0L)
        val measuredModelLatencyMs = detectorInferenceLatencyMs + landmarkInferenceLatencyMs
        val pipelineLatencyMs = maxOf(totalLatencyMs, measuredModelLatencyMs)

        return HandTrackingFrame(
            timestampMs = frame.timestampMs,
            hands = handsWithPinch,
            pipelineLatencyMs = pipelineLatencyMs,
            sourceWidthPx = frame.widthPx,
            sourceHeightPx = frame.heightPx,
            inputWidthPx = frame.widthPx,
            inputHeightPx = frame.heightPx,
            preprocessingLatencyMs = preprocessingLatencyMs
        )
    }

    fun reset() {
        tracks.clear()
        pinchTracker.reset()
        framesSinceDetection = detectorIntervalFrames
        lastFrameTimestampMs = 0L
    }

    private fun mergeDetections(detections: List<LiteRtPalmRegion>) {
        if (tracks.size >= maxHands) return
        detections.asSequence()
            .filter { it.confidence >= minimumPalmConfidence }
            .sortedByDescending { it.confidence }
            .forEach { candidate ->
                if (tracks.size >= maxHands) return
                val overlapsExisting = tracks.values.any { existing ->
                    intersectionOverUnion(existing.region, candidate) >= DUPLICATE_ROI_IOU ||
                        centersAreNear(existing.region, candidate)
                }
                if (!overlapsExisting) {
                    val id = nextTrackId++
                    tracks[id] = Track(id, candidate)
                }
            }
    }

    private fun updateRegion(
        previous: LiteRtPalmRegion,
        points: List<HandPoint>
    ): LiteRtPalmRegion {
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        points.forEach { point ->
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        val width = ((maxX - minX) * ROI_EXPANSION).coerceAtLeast(MINIMUM_TRACKED_ROI)
        val height = ((maxY - minY) * ROI_EXPANSION).coerceAtLeast(MINIMUM_TRACKED_ROI)
        return previous.copy(
            centerX = ((minX + maxX) * 0.5f).coerceIn(0f, 1f),
            centerY = ((minY + maxY) * 0.5f).coerceIn(0f, 1f),
            width = width.coerceAtMost(1f),
            height = height.coerceAtMost(1f)
        )
    }

    private fun hasValidLandmarks(prediction: LiteRtHandLandmarkPrediction): Boolean =
        prediction.points.size == LANDMARK_COUNT &&
            prediction.points.all {
                isFinite(it) && it.x in 0f..1f && it.y in 0f..1f
            } &&
            (prediction.worldPoints.isEmpty() ||
                (prediction.worldPoints.size == LANDMARK_COUNT &&
                    prediction.worldPoints.all(::isFinite)))

    private fun isFinite(point: HandPoint): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

    private fun centersAreNear(a: LiteRtPalmRegion, b: LiteRtPalmRegion): Boolean {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        val maxWidth = maxOf(a.width, b.width)
        val maxHeight = maxOf(a.height, b.height)
        return dx * dx + dy * dy <=
            (maxWidth * maxWidth + maxHeight * maxHeight) * CENTER_MATCH_DISTANCE_SQUARED
    }

    private fun intersectionOverUnion(a: LiteRtPalmRegion, b: LiteRtPalmRegion): Float {
        val aLeft = a.centerX - a.width * 0.5f
        val aTop = a.centerY - a.height * 0.5f
        val aRight = a.centerX + a.width * 0.5f
        val aBottom = a.centerY + a.height * 0.5f
        val bLeft = b.centerX - b.width * 0.5f
        val bTop = b.centerY - b.height * 0.5f
        val bRight = b.centerX + b.width * 0.5f
        val bBottom = b.centerY + b.height * 0.5f
        val intersectionWidth = (minOf(aRight, bRight) - maxOf(aLeft, bLeft)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(aBottom, bBottom) - maxOf(aTop, bTop)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        const val LANDMARK_COUNT = 21
        const val DEFAULT_MAX_HANDS = 2
        const val DEFAULT_DETECTOR_INTERVAL_FRAMES = 8
        const val DEFAULT_MAX_CONSECUTIVE_MISSES = 2
        const val DEFAULT_MIN_PALM_CONFIDENCE = 0.50f
        const val DEFAULT_MIN_PRESENCE_SCORE = 0.45f
        private const val DUPLICATE_ROI_IOU = 0.20f
        private const val CENTER_MATCH_DISTANCE_SQUARED = 0.16f
        private const val ROI_EXPANSION = 1.35f
        private const val MINIMUM_TRACKED_ROI = 0.08f
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
