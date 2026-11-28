package com.noyclic.questphonexr.tracking

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * One row from Qualcomm's `anchors_palm.npy`: normalized X/Y offset followed by X/Y
 * decode scales. Values are kept explicit so a caller must load the anchors belonging to the
 * exact palm model revision; the decoder never fabricates an anchor grid from N alone.
 */
data class QualcommPalmAnchor(
    val offsetX: Float,
    val offsetY: Float,
    val scaleX: Float,
    val scaleY: Float
) {
    fun isValid(): Boolean =
        offsetX.isFinite() && offsetY.isFinite() &&
            scaleX.isFinite() && scaleY.isFinite() &&
            offsetX in 0f..1f && offsetY in 0f..1f &&
            scaleX > 0f && scaleY > 0f
}

/**
 * Geometry shared by the palm detector's letterbox preprocessor and output decoder.
 *
 * Use the same instance for preprocessing and decoding. Content dimensions are integer pixels,
 * so source mapping remains consistent with Qualcomm's nominal resize scale and integer output dimensions.
 */
data class LiteRtPalmInputTransform(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val inputWidthPx: Int,
    val inputHeightPx: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val padLeftPx: Int,
    val padTopPx: Int
) {
    init {
        require(sourceWidthPx > 0 && sourceHeightPx > 0)
        require(inputWidthPx > 0 && inputHeightPx > 0)
        require(contentWidthPx in 1..inputWidthPx)
        require(contentHeightPx in 1..inputHeightPx)
        require(padLeftPx >= 0 && padTopPx >= 0)
        require(padLeftPx + contentWidthPx <= inputWidthPx)
        require(padTopPx + contentHeightPx <= inputHeightPx)
    }

    /** Nominal resize scale returned by Qualcomm's resize_pad helper. */
    val resizeScale: Float
        get() = minOf(
            inputWidthPx.toFloat() / sourceWidthPx,
            inputHeightPx.toFloat() / sourceHeightPx
        )

    fun toSourcePixel(modelX: Float, modelY: Float): Pair<Float, Float>? {
        if (!modelX.isFinite() || !modelY.isFinite()) return null
        val scale = resizeScale
        return (modelX - padLeftPx) / scale to
            (modelY - padTopPx) / scale
    }

    fun toSourceNormalized(modelX: Float, modelY: Float): Pair<Float, Float>? {
        val pixel = toSourcePixel(modelX, modelY) ?: return null
        return pixel.first / sourceWidthPx to pixel.second / sourceHeightPx
    }

    fun containsSourcePixel(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() &&
            x in 0f..sourceWidthPx.toFloat() &&
            y in 0f..sourceHeightPx.toFloat()

    companion object {
        /**
         * Builds centered letterbox geometry for a full-frame RGB resize.
         * The corresponding preprocessor must resize to [contentWidthPx] x [contentHeightPx]
         * and place that image at [padLeftPx], [padTopPx] in the model input.
         */
        fun centeredLetterbox(
            sourceWidthPx: Int,
            sourceHeightPx: Int,
            inputWidthPx: Int = QualcommPalmOutputDecoder.MODEL_INPUT_SIZE_PX,
            inputHeightPx: Int = QualcommPalmOutputDecoder.MODEL_INPUT_SIZE_PX
        ): LiteRtPalmInputTransform {
            require(sourceWidthPx > 0 && sourceHeightPx > 0)
            require(inputWidthPx > 0 && inputHeightPx > 0)
            val scale = minOf(
                inputWidthPx.toFloat() / sourceWidthPx,
                inputHeightPx.toFloat() / sourceHeightPx
            )
            val contentWidth = floor(sourceWidthPx * scale).toInt().coerceIn(1, inputWidthPx)
            val contentHeight = floor(sourceHeightPx * scale).toInt().coerceIn(1, inputHeightPx)
            return LiteRtPalmInputTransform(
                sourceWidthPx = sourceWidthPx,
                sourceHeightPx = sourceHeightPx,
                inputWidthPx = inputWidthPx,
                inputHeightPx = inputHeightPx,
                contentWidthPx = contentWidth,
                contentHeightPx = contentHeight,
                padLeftPx = (inputWidthPx - contentWidth) / 2,
                padTopPx = (inputHeightPx - contentHeight) / 2
            )
        }
    }
}

data class QualcommPalmDetectorTensors(
    /** Raw center/size and seven keypoints: [1,N,18] or [1,N,9,2]. */
    val coordinates: LiteRtFloatTensor,
    /** Raw confidence logits: [1,N] or [1,N,1]. */
    val scores: LiteRtFloatTensor
)

enum class QualcommPalmDecodeRejection {
    INVALID_THRESHOLDS,
    INVALID_FRAME_TRANSFORM,
    INVALID_COORDINATE_SHAPE,
    INVALID_SCORE_SHAPE,
    ANCHOR_COUNT_MISMATCH,
    INVALID_ANCHOR,
    NON_FINITE_OUTPUT
}

sealed class QualcommPalmDecodeResult {
    data class Success(
        val regions: List<LiteRtPalmRegion>,
        val candidatesAfterThreshold: Int,
        val candidatesAfterNms: Int
    ) : QualcommPalmDecodeResult()

    data class Rejected(val reason: QualcommPalmDecodeRejection) : QualcommPalmDecodeResult()
}

/**
 * Pure Kotlin post-processing for Qualcomm AI Hub's MediaPipe-Hand palm detector.
 *
 * This follows the pinned Qualcomm MediaPipe-Hand contract:
 * - input geometry is model-specific (256x256 for the current published model);
 * - box and seven keypoint values are decoded against the model's [N,2,2] anchors;
 * - confidence is clipped to [-100,100] then passed through sigmoid;
 * - box coordinates are center/size and converted to corners;
 * - class-agnostic NMS is applied to the palm boxes using the Qualcomm AI Hub app contract;
 * - keypoints 0 (wrist) and 2 (middle-finger finder) orient and shift the hand ROI.
 *
 * The caller supplies anchors from the matching `anchors_palm.npy`. A valid N is checked
 * against both output tensors and the anchor array rather than guessed from stale comments
 * in the upstream network definition. TensorBuffer output must be dequantized before calling.
 *
 * This is model post-processing only. It does not create a LiteRT interpreter, select a QNN
 * backend, load the anchor asset, or claim that DSP/HTP inference has run on a device.
 */
object QualcommPalmOutputDecoder {
    const val MODEL_INPUT_SIZE_PX = 256
    const val COORDINATES_PER_ANCHOR = 18
    const val KEYPOINT_COUNT = 7
    const val WRIST_KEYPOINT_INDEX = 0
    const val MIDDLE_FINDER_KEYPOINT_INDEX = 2
    const val DEFAULT_SCORE_THRESHOLD = 0.95f
    const val DEFAULT_NMS_IOU_THRESHOLD = 0.30f
    const val DEFAULT_MAX_HANDS = 2
    const val SCORE_CLIP_LIMIT = 100f

    // Pinned Qualcomm AI Hub MediaPipe-Hand app.py configuration.
    private const val ROI_DIRECTIONAL_OFFSET = 0.5f
    private const val ROI_SCALE = 2.5f
    private const val MIN_VECTOR_LENGTH_PX = 0.0001f

    private data class Candidate(
        val anchorIndex: Int,
        val score: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val keypoints: List<Pair<Float, Float>>
    )

    fun decode(
        outputs: QualcommPalmDetectorTensors,
        anchors: List<QualcommPalmAnchor>,
        inputTransform: LiteRtPalmInputTransform,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        nmsIouThreshold: Float = DEFAULT_NMS_IOU_THRESHOLD,
        maxHands: Int = DEFAULT_MAX_HANDS
    ): QualcommPalmDecodeResult {
        if (!scoreThreshold.isFinite() || scoreThreshold !in 0f..1f ||
            !nmsIouThreshold.isFinite() || nmsIouThreshold !in 0f..1f ||
            maxHands !in 1..2
        ) {
            return reject(QualcommPalmDecodeRejection.INVALID_THRESHOLDS)
        }

        val coordinateShape = outputs.coordinates.shape
        val scoreShape = outputs.scores.shape
        if (coordinateShape.size != 3 && coordinateShape.size != 4) {
            return reject(QualcommPalmDecodeRejection.INVALID_COORDINATE_SHAPE)
        }
        val anchorCount = when {
            coordinateShape.size == 3 &&
                coordinateShape[0] == 1 &&
                coordinateShape[2] == COORDINATES_PER_ANCHOR ->
                coordinateShape[1]
            coordinateShape.size == 4 &&
                coordinateShape[0] == 1 &&
                coordinateShape[2] == KEYPOINT_COUNT + 2 &&
                coordinateShape[3] == 2 ->
                coordinateShape[1]
            else -> return reject(QualcommPalmDecodeRejection.INVALID_COORDINATE_SHAPE)
        }
        if (anchorCount <= 0 ||
            outputs.coordinates.values.size.toLong() != anchorCount.toLong() * COORDINATES_PER_ANCHOR
        ) {
            return reject(QualcommPalmDecodeRejection.INVALID_COORDINATE_SHAPE)
        }

        val scoreAnchorCount = when {
            scoreShape.size == 2 && scoreShape[0] == 1 -> scoreShape[1]
            scoreShape.size == 3 && scoreShape[0] == 1 && scoreShape[2] == 1 -> scoreShape[1]
            else -> return reject(QualcommPalmDecodeRejection.INVALID_SCORE_SHAPE)
        }
        if (scoreAnchorCount != anchorCount ||
            outputs.scores.values.size != anchorCount
        ) {
            return reject(QualcommPalmDecodeRejection.INVALID_SCORE_SHAPE)
        }
        if (anchors.size != anchorCount) {
            return reject(QualcommPalmDecodeRejection.ANCHOR_COUNT_MISMATCH)
        }
        if (anchors.any { !it.isValid() }) {
            return reject(QualcommPalmDecodeRejection.INVALID_ANCHOR)
        }
        if (!outputs.coordinates.values.all(Float::isFinite) ||
            !outputs.scores.values.all(Float::isFinite)
        ) {
            return reject(QualcommPalmDecodeRejection.NON_FINITE_OUTPUT)
        }

        val candidates = ArrayList<Candidate>()
        val inputWidth = inputTransform.inputWidthPx.toFloat()
        val inputHeight = inputTransform.inputHeightPx.toFloat()

        for (index in 0 until anchorCount) {
            val score = sigmoid(outputs.scores.values[index])
            if (score < scoreThreshold) continue

            val offset = index * COORDINATES_PER_ANCHOR
            val anchor = anchors[index]
            val centerX = outputs.coordinates.values[offset] * anchor.scaleX +
                anchor.offsetX * inputWidth
            val centerY = outputs.coordinates.values[offset + 1] * anchor.scaleY +
                anchor.offsetY * inputHeight
            val boxWidth = outputs.coordinates.values[offset + 2] * anchor.scaleX
            val boxHeight = outputs.coordinates.values[offset + 3] * anchor.scaleY
            if (!boxWidth.isFinite() || !boxHeight.isFinite() ||
                boxWidth <= 0f || boxHeight <= 0f
            ) continue

            val keypoints = ArrayList<Pair<Float, Float>>(KEYPOINT_COUNT)
            var validKeypoints = true
            for (keypointIndex in 0 until KEYPOINT_COUNT) {
                val keypointOffset = offset + 4 + keypointIndex * 2
                val x = outputs.coordinates.values[keypointOffset] * anchor.scaleX +
                    anchor.offsetX * inputWidth
                val y = outputs.coordinates.values[keypointOffset + 1] * anchor.scaleY +
                    anchor.offsetY * inputHeight
                val source = inputTransform.toSourcePixel(x, y)
                if (source == null || !inputTransform.containsSourcePixel(source.first, source.second)) {
                    validKeypoints = false
                    break
                }
                keypoints += source
            }
            if (!validKeypoints) continue

            val left = centerX - boxWidth * 0.5f
            val top = centerY - boxHeight * 0.5f
            val right = centerX + boxWidth * 0.5f
            val bottom = centerY + boxHeight * 0.5f
            val sourceTopLeft = inputTransform.toSourcePixel(left, top) ?: continue
            val sourceBottomRight = inputTransform.toSourcePixel(right, bottom) ?: continue
            if (!inputTransform.containsSourcePixel(sourceTopLeft.first, sourceTopLeft.second) ||
                !inputTransform.containsSourcePixel(sourceBottomRight.first, sourceBottomRight.second)
            ) continue

            candidates += Candidate(
                anchorIndex = index,
                score = score,
                left = sourceTopLeft.first,
                top = sourceTopLeft.second,
                right = sourceBottomRight.first,
                bottom = sourceBottomRight.second,
                keypoints = keypoints
            )
        }

        val selected = nonMaximumSuppression(candidates, nmsIouThreshold, maxHands)
        val regions = selected.mapNotNull { candidate ->
            candidate.toRegion(inputTransform.sourceWidthPx, inputTransform.sourceHeightPx)
        }
        return QualcommPalmDecodeResult.Success(
            regions = regions,
            candidatesAfterThreshold = candidates.size,
            candidatesAfterNms = selected.size
        )
    }

    private fun Candidate.toRegion(sourceWidthPx: Int, sourceHeightPx: Int): LiteRtPalmRegion? {
        val wrist = keypoints[WRIST_KEYPOINT_INDEX]
        val middleFinder = keypoints[MIDDLE_FINDER_KEYPOINT_INDEX]
        val vectorX = middleFinder.first - wrist.first
        val vectorY = middleFinder.second - wrist.second
        val vectorLength = sqrt(vectorX * vectorX + vectorY * vectorY)
        val boxWidth = right - left
        val boxHeight = bottom - top
        if (!vectorLength.isFinite() || vectorLength <= MIN_VECTOR_LENGTH_PX ||
            !boxWidth.isFinite() || !boxHeight.isFinite() ||
            boxWidth <= 0f || boxHeight <= 0f
        ) return null

        var centerX = (left + right) * 0.5f
        var centerY = (top + bottom) * 0.5f
        // Qualcomm AI Hub's MediaPipeHandApp applies a directional offset along the
        // wrist-to-middle-finder vector, scaled by the detector box width.
        val directionalOffset = ROI_DIRECTIONAL_OFFSET * boxWidth
        centerX += directionalOffset * vectorX / vectorLength
        centerY += directionalOffset * vectorY / vectorLength + ROI_VERTICAL_SHIFT * boxWidth

        // Qualcomm's reference computes atan2(startY-endY, startX-endX) - PI/2.
        val rotation = atan2(wrist.second - middleFinder.second, wrist.first - middleFinder.first) -
            (PI.toFloat() * 0.5f)
        val regionWidth = boxWidth * ROI_SCALE / sourceWidthPx
        val regionHeight = boxHeight * ROI_SCALE / sourceHeightPx
        if (!centerX.isFinite() || !centerY.isFinite() ||
            !rotation.isFinite() || !regionWidth.isFinite() || !regionHeight.isFinite() ||
            regionWidth <= 0f || regionHeight <= 0f
        ) return null

        return LiteRtPalmRegion(
            centerX = centerX / sourceWidthPx,
            centerY = centerY / sourceHeightPx,
            width = regionWidth,
            height = regionHeight,
            rotationRadians = rotation,
            confidence = score
        )
    }

    private fun nonMaximumSuppression(
        candidates: List<Candidate>,
        iouThreshold: Float,
        maxHands: Int
    ): List<Candidate> {
        val remaining = candidates.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.anchorIndex }
        ).toMutableList()
        val selected = ArrayList<Candidate>(maxHands)
        while (remaining.isNotEmpty() && selected.size < maxHands) {
            val best = remaining.removeAt(0)
            selected += best
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                if (intersectionOverUnion(best, iterator.next()) > iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun intersectionOverUnion(a: Candidate, b: Candidate): Float {
        val intersectionWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun sigmoid(logit: Float): Float {
        val clipped = logit.coerceIn(-SCORE_CLIP_LIMIT, SCORE_CLIP_LIMIT).toDouble()
        return (1.0 / (1.0 + exp(-clipped))).toFloat()
    }

    private fun reject(reason: QualcommPalmDecodeRejection) =
        QualcommPalmDecodeResult.Rejected(reason)
}
