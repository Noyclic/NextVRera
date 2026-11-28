package com.noyclic.questphonexr.tracking

data class QualcommHandLandmarkTensors(
    /** Sigmoid hand-presence output, normally shape [1] or [1,1]. */
    val handPresence: LiteRtFloatTensor,
    /** Sigmoid probability that the detected hand is the right hand. */
    val probabilityIsRightHand: LiteRtFloatTensor,
    /** 21 normalized (x,y,z) triples, normally shape [1,21,3]. */
    val landmarks: LiteRtFloatTensor
)

enum class QualcommHandLandmarkDecodeRejection {
    INVALID_FRAME_TIMESTAMP,
    INVALID_PIPELINE_LATENCY,
    INVALID_PRESENCE_THRESHOLD,
    INVALID_HANDEDNESS_DEAD_ZONE,
    INVALID_ROI_INPUT_SIZE,
    INVALID_TENSOR_SHAPE,
    NON_FINITE_OUTPUT,
    INVALID_PRESENCE_SCORE,
    INVALID_HANDEDNESS_SCORE,
    NO_HAND_PRESENT,
    INVALID_LANDMARK_COORDINATE,
    LANDMARK_OUTSIDE_SOURCE_FRAME
}

sealed class QualcommHandLandmarkDecodeResult {
    data class Success(
        val prediction: LiteRtHandLandmarkPrediction,
        val handednessConfidence: Float
    ) : QualcommHandLandmarkDecodeResult()

    data class Rejected(
        val reason: QualcommHandLandmarkDecodeRejection
    ) : QualcommHandLandmarkDecodeResult()
}

/**
 * Decodes Qualcomm's BlazeHandLandmark output tuple into the standalone LiteRT tracker contract.
 *
 * The pinned model returns a sigmoid hand flag, a sigmoid probability-is-right value and
 * 21 (x,y,z) points normalized by the 256px landmark input. X/Y are mapped through the exact
 * inverse three-point affine crop that produced the model input. Z remains hand-local model
 * depth; no metric world coordinates are invented for this model.
 *
 * Keep the output tensors associated by their model signature names (scores, lr, landmarks),
 * not by a presumed positional order from a delegate API.
 */
object QualcommHandLandmarkOutputDecoder {
    const val MODEL_INPUT_SIZE_PX = 256
    const val LANDMARK_COUNT = 21
    const val VALUES_PER_LANDMARK = 3
    const val LANDMARK_VALUE_COUNT = LANDMARK_COUNT * VALUES_PER_LANDMARK
    const val DEFAULT_MINIMUM_PRESENCE = 0.50f
    const val DEFAULT_HANDEDNESS_DEAD_ZONE = 0.10f
    const val MAX_CROP_COORDINATE_MAGNITUDE = 1.5f
    const val MAX_NORMALIZED_DEPTH_MAGNITUDE = 2f

    fun decode(
        outputs: QualcommHandLandmarkTensors,
        roiTransform: QualcommHandRoiTransform,
        frameTimestampMs: Long,
        pipelineLatencyMs: Long = 0L,
        minimumPresence: Float = DEFAULT_MINIMUM_PRESENCE,
        handednessDeadZone: Float = DEFAULT_HANDEDNESS_DEAD_ZONE,
        preprocessingLatencyMs: Long = 0L,
        inferenceLatencyMs: Long = 0L
    ): QualcommHandLandmarkDecodeResult {
        if (frameTimestampMs <= 0L) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_FRAME_TIMESTAMP)
        }
        if (pipelineLatencyMs < 0L || preprocessingLatencyMs < 0L || inferenceLatencyMs < 0L) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_PIPELINE_LATENCY)
        }
        if (!minimumPresence.isFinite() || minimumPresence !in 0f..1f) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_PRESENCE_THRESHOLD)
        }
        if (!handednessDeadZone.isFinite() || handednessDeadZone !in 0f..0.49f) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_HANDEDNESS_DEAD_ZONE)
        }
        if (roiTransform.inputWidthPx <= 1 || roiTransform.inputHeightPx <= 1) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_ROI_INPUT_SIZE)
        }

        if (!outputs.handPresence.isScalar() ||
            !outputs.probabilityIsRightHand.isScalar() ||
            !outputs.landmarks.hasLandmarkShape()
        ) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_TENSOR_SHAPE)
        }
        if (!outputs.handPresence.values.all(Float::isFinite) ||
            !outputs.probabilityIsRightHand.values.all(Float::isFinite) ||
            !outputs.landmarks.values.all(Float::isFinite)
        ) {
            return reject(QualcommHandLandmarkDecodeRejection.NON_FINITE_OUTPUT)
        }

        val presence = outputs.handPresence.values[0]
        if (presence !in 0f..1f) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_PRESENCE_SCORE)
        }
        if (presence < minimumPresence) {
            return reject(QualcommHandLandmarkDecodeRejection.NO_HAND_PRESENT)
        }

        val probabilityIsRight = outputs.probabilityIsRightHand.values[0]
        if (probabilityIsRight !in 0f..1f) {
            return reject(QualcommHandLandmarkDecodeRejection.INVALID_HANDEDNESS_SCORE)
        }

        val points = ArrayList<HandPoint>(LANDMARK_COUNT)
        for (index in 0 until LANDMARK_COUNT) {
            val offset = index * VALUES_PER_LANDMARK
            val normalizedX = outputs.landmarks.values[offset]
            val normalizedY = outputs.landmarks.values[offset + 1]
            val normalizedZ = outputs.landmarks.values[offset + 2]

            if (normalizedX !in -MAX_CROP_COORDINATE_MAGNITUDE..(1f + MAX_CROP_COORDINATE_MAGNITUDE) ||
                normalizedY !in -MAX_CROP_COORDINATE_MAGNITUDE..(1f + MAX_CROP_COORDINATE_MAGNITUDE) ||
                normalizedZ !in -MAX_NORMALIZED_DEPTH_MAGNITUDE..MAX_NORMALIZED_DEPTH_MAGNITUDE
            ) {
                return reject(QualcommHandLandmarkDecodeRejection.INVALID_LANDMARK_COORDINATE)
            }

            // BlazeHandLandmark divides pixel outputs by its 256px input resolution.
            val source = roiTransform.toSourceNormalized(
                normalizedX * roiTransform.inputWidthPx,
                normalizedY * roiTransform.inputHeightPx
            ) ?: return reject(QualcommHandLandmarkDecodeRejection.INVALID_LANDMARK_COORDINATE)

            if (source.first !in 0f..1f || source.second !in 0f..1f) {
                return reject(QualcommHandLandmarkDecodeRejection.LANDMARK_OUTSIDE_SOURCE_FRAME)
            }
            points += HandPoint(
                x = source.first,
                y = source.second,
                z = normalizedZ
            )
        }

        val handednessConfidence = (kotlin.math.abs(probabilityIsRight - 0.5f) * 2f)
            .coerceIn(0f, 1f)
        val side = when {
            probabilityIsRight < 0.5f - handednessDeadZone -> HandSide.LEFT
            probabilityIsRight > 0.5f + handednessDeadZone -> HandSide.RIGHT
            else -> HandSide.UNKNOWN
        }

        return QualcommHandLandmarkDecodeResult.Success(
            prediction = LiteRtHandLandmarkPrediction(
                points = points,
                presenceScore = presence,
                side = side,
                preprocessingLatencyMs = preprocessingLatencyMs,
                inferenceLatencyMs = inferenceLatencyMs
            ),
            handednessConfidence = handednessConfidence
        )
    }

    private fun LiteRtFloatTensor.isScalar(): Boolean =
        (shape.contentEquals(intArrayOf(1)) ||
            shape.contentEquals(intArrayOf(1, 1))) && values.size == 1

    private fun LiteRtFloatTensor.hasLandmarkShape(): Boolean =
        (shape.contentEquals(intArrayOf(1, LANDMARK_COUNT, VALUES_PER_LANDMARK)) ||
            shape.contentEquals(intArrayOf(1, LANDMARK_VALUE_COUNT))) &&
            values.size == LANDMARK_VALUE_COUNT

    private fun reject(reason: QualcommHandLandmarkDecodeRejection) =
        QualcommHandLandmarkDecodeResult.Rejected(reason)
}
