package com.noyclic.questphonexr.tracking

/**
 * One inference result from the Qualcomm palm detector model.
 *
 * The engine owns its runtime/backend and must return dequantized FLOAT values in the
 * documented model output order. It must not silently substitute another model.
 */
data class QualcommPalmInferenceOutput(
    val coordinates: LiteRtFloatTensor,
    val scores: LiteRtFloatTensor
)

/**
 * Backend-neutral palm model boundary. Implementations may use QNN HTP, GPU or CPU, but
 * the selected backend must be reported by the implementation and never inferred here.
 */
fun interface QualcommPalmInferenceEngine {
    fun infer(inputNchwRgbFloat: FloatArray): QualcommPalmInferenceOutput
}

/** One inference result from the Qualcomm hand-landmark model. */
data class QualcommHandLandmarkInferenceOutput(
    val handPresence: LiteRtFloatTensor,
    val probabilityIsRightHand: LiteRtFloatTensor,
    val landmarks: LiteRtFloatTensor
)

/**
 * Backend-neutral landmark model boundary. The input is one upright 256x256 RGB crop in
 * NCHW float [0,1] layout. One call predicts one ROI; callers can batch by invoking once
 * for each ROI or by implementing a future batched engine.
 */
fun interface QualcommHandLandmarkInferenceEngine {
    fun infer(inputNchwRgbFloat: FloatArray): QualcommHandLandmarkInferenceOutput
}

/**
 * Explicit exception for malformed model outputs. A rejected tensor contract must not be
 * disguised as "no hand detected", because that would make backend/model failures invisible.
 */
class QualcommModelOutputException(message: String) : IllegalStateException(message)

/**
 * Connects the pure Qualcomm palm preprocessing/decoder to the existing independent
 * LiteRT tracker interface.
 *
 * This class does not initialize LiteRT or select an accelerator. Its injected engine is
 * responsible for real model execution. The same verified anchor set is retained for the
 * lifetime of this adapter.
 *
 * [sourceRotationDegrees] describes the raw pixel buffer relative to the upright frame
 * dimensions carried by [LiteRtCameraFrame]. The frame pixel array remains borrowed and is
 * never retained after [detect] returns.
 */
class QualcommLiteRtPalmDetector(
    private val engine: QualcommPalmInferenceEngine,
    private val anchors: List<QualcommPalmAnchor>,
    private val sourceRotationDegrees: Int = 0,
    private val scoreThreshold: Float = QualcommPalmOutputDecoder.DEFAULT_SCORE_THRESHOLD,
    private val nmsIouThreshold: Float = QualcommPalmOutputDecoder.DEFAULT_NMS_IOU_THRESHOLD,
    private val maxHands: Int = QualcommPalmOutputDecoder.DEFAULT_MAX_HANDS
) : LiteRtPalmDetector {

    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedTransform: LiteRtPalmInputTransform? = null
    private var inputScratch = FloatArray(0)

    init {
        require(anchors.size == QualcommPalmAnchorAssetLoader.EXPECTED_ANCHOR_COUNT) {
            "Qualcomm palm adapter requires the complete pinned anchor asset."
        }
        require(anchors.all { it.isValid() }) { "Qualcomm palm anchors contain invalid values." }
        require(maxHands in 1..2)
    }

    @Synchronized
    override fun detect(frame: LiteRtCameraFrame): LiteRtPalmDetectionBatch {
        val transform = geometryFor(frame)
        val preprocessStarted = System.nanoTime()
        check(
            QualcommPalmInputPreprocessor.writeNchwRgbFloat(
                sourceArgb = frame.argbPixels,
                transform = transform,
                destinationNchw = inputScratch,
                sourceRotationDegrees = sourceRotationDegrees
            )
        ) { "Qualcomm palm input preprocessing rejected the camera frame." }
        val preprocessingLatencyMs = elapsedMillis(preprocessStarted)

        val inferenceStarted = System.nanoTime()
        val output = engine.infer(inputScratch)
        val inferenceLatencyMs = elapsedMillis(inferenceStarted)

        val decoded = QualcommPalmOutputDecoder.decode(
            outputs = QualcommPalmDetectorTensors(
                coordinates = output.coordinates,
                scores = output.scores
            ),
            anchors = anchors,
            inputTransform = transform,
            scoreThreshold = scoreThreshold,
            nmsIouThreshold = nmsIouThreshold,
            maxHands = maxHands
        )
        val success = decoded as? QualcommPalmDecodeResult.Success
            ?: throw QualcommModelOutputException(
                "Qualcomm palm output contract rejected: " +
                    (decoded as QualcommPalmDecodeResult.Rejected).reason
            )

        return LiteRtPalmDetectionBatch(
            regions = success.regions,
            preprocessingLatencyMs = preprocessingLatencyMs,
            inferenceLatencyMs = inferenceLatencyMs
        )
    }

    private fun geometryFor(frame: LiteRtCameraFrame): LiteRtPalmInputTransform {
        if (cachedTransform == null ||
            cachedWidth != frame.widthPx ||
            cachedHeight != frame.heightPx
        ) {
            cachedWidth = frame.widthPx
            cachedHeight = frame.heightPx
            cachedTransform = LiteRtPalmInputTransform.centeredLetterbox(
                sourceWidthPx = frame.widthPx,
                sourceHeightPx = frame.heightPx,
                inputWidthPx = QualcommPalmOutputDecoder.MODEL_INPUT_SIZE_PX,
                inputHeightPx = QualcommPalmOutputDecoder.MODEL_INPUT_SIZE_PX
            )
            inputScratch = FloatArray(
                QualcommPalmOutputDecoder.MODEL_INPUT_SIZE_PX *
                    QualcommPalmOutputDecoder.MODEL_INPUT_SIZE_PX * CHANNELS
            )
        }
        return checkNotNull(cachedTransform)
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    companion object {
        private const val CHANNELS = 3
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * Connects Qualcomm's oriented ROI crop and landmark decoder to the existing independent
 * LiteRT tracker interface. A separate reusable input buffer is held for this model.
 *
 * Presence-score rejection means a normal "no hand" observation and returns null. Malformed
 * shapes, non-finite values and invalid coordinates are surfaced as model contract failures.
 */
class QualcommLiteRtHandLandmarkEstimator(
    private val engine: QualcommHandLandmarkInferenceEngine,
    private val sourceRotationDegrees: Int = 0,
    private val minimumPresence: Float =
        QualcommHandLandmarkOutputDecoder.DEFAULT_MINIMUM_PRESENCE,
    private val handednessDeadZone: Float =
        QualcommHandLandmarkOutputDecoder.DEFAULT_HANDEDNESS_DEAD_ZONE
) : LiteRtHandLandmarkEstimator {

    private var cachedWidth = 0
    private var cachedHeight = 0
    private var inputScratch = FloatArray(0)

    init {
        require(minimumPresence.isFinite() && minimumPresence in 0f..1f)
        require(handednessDeadZone.isFinite() && handednessDeadZone in 0f..0.49f)
    }

    @Synchronized
    override fun estimate(
        frame: LiteRtCameraFrame,
        region: LiteRtPalmRegion
    ): LiteRtHandLandmarkPrediction? {
        val transform = QualcommHandRoiTransform.fromPalmRegion(
            region = region,
            sourceWidthPx = frame.widthPx,
            sourceHeightPx = frame.heightPx,
            inputWidthPx = QualcommHandLandmarkOutputDecoder.MODEL_INPUT_SIZE_PX,
            inputHeightPx = QualcommHandLandmarkOutputDecoder.MODEL_INPUT_SIZE_PX
        )
        ensureInputBuffer()
        val preprocessStarted = System.nanoTime()
        check(
            QualcommHandRoiPreprocessor.writeNchwRgbFloat(
                sourceArgb = frame.argbPixels,
                transform = transform,
                destinationNchw = inputScratch,
                sourceRotationDegrees = sourceRotationDegrees
            )
        ) { "Qualcomm landmark ROI preprocessing rejected the camera frame." }
        val preprocessingLatencyMs = elapsedMillis(preprocessStarted)

        val inferenceStarted = System.nanoTime()
        val output = engine.infer(inputScratch)
        val inferenceLatencyMs = elapsedMillis(inferenceStarted)

        val decoded = QualcommHandLandmarkOutputDecoder.decode(
            outputs = QualcommHandLandmarkTensors(
                handPresence = output.handPresence,
                probabilityIsRightHand = output.probabilityIsRightHand,
                landmarks = output.landmarks
            ),
            roiTransform = transform,
            frameTimestampMs = frame.timestampMs,
            minimumPresence = minimumPresence,
            handednessDeadZone = handednessDeadZone,
            preprocessingLatencyMs = preprocessingLatencyMs,
            inferenceLatencyMs = inferenceLatencyMs
        )
        return when (decoded) {
            is QualcommHandLandmarkDecodeResult.Success -> decoded.prediction
            is QualcommHandLandmarkDecodeResult.Rejected -> {
                if (decoded.reason == QualcommHandLandmarkDecodeRejection.NO_HAND_PRESENT) {
                    null
                } else {
                    throw QualcommModelOutputException(
                        "Qualcomm landmark output contract rejected: ${decoded.reason}"
                    )
                }
            }
        }
    }

    private fun ensureInputBuffer() {
        val required = QualcommHandLandmarkOutputDecoder.MODEL_INPUT_SIZE_PX *
            QualcommHandLandmarkOutputDecoder.MODEL_INPUT_SIZE_PX * CHANNELS
        if (inputScratch.size != required || cachedWidth == 0 || cachedHeight == 0) {
            inputScratch = FloatArray(required)
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    companion object {
        private const val CHANNELS = 3
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * Convenience factory for the standalone Qualcomm tracker. Both engines are injected
 * separately so detector and landmark models can use different measured accelerators.
 */
object QualcommStandaloneTrackerFactory {
    fun create(
        context: android.content.Context,
        palmEngine: QualcommPalmInferenceEngine,
        landmarkEngine: QualcommHandLandmarkInferenceEngine,
        sourceRotationDegrees: Int = 0,
        maxHands: Int = LiteRtStandaloneTracker.DEFAULT_MAX_HANDS
    ): LiteRtStandaloneTracker {
        val anchors = QualcommPalmAnchorAssetLoader.load(context)
        return LiteRtStandaloneTracker(
            palmDetector = QualcommLiteRtPalmDetector(
                engine = palmEngine,
                anchors = anchors,
                sourceRotationDegrees = sourceRotationDegrees,
                maxHands = maxHands
            ),
            landmarkEstimator = QualcommLiteRtHandLandmarkEstimator(
                engine = landmarkEngine,
                sourceRotationDegrees = sourceRotationDegrees
            ),
            maxHands = maxHands
        )
    }
}
