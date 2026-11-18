package com.noyclic.hardwareprobe

import kotlin.math.sqrt

internal data class TensorComparisonResult(
    val cosineSimilarity: Double,
    val meanAbsoluteError: Double,
    val maxAbsoluteError: Double,
    val exactMatchPercent: Double,
    val referenceTopIndex: Int,
    val candidateTopIndex: Int,
    val top1Agreement: Boolean
)

internal object QuantizedTensorComparator {
    fun compare(
        reference: ByteArray,
        candidate: ByteArray,
        signed: Boolean,
        scale: Float,
        zeroPoint: Int
    ): TensorComparisonResult {
        require(reference.isNotEmpty()) { "Reference tensor is empty." }
        require(reference.size == candidate.size) {
            "Tensor sizes differ: reference=${reference.size}, candidate=${candidate.size}."
        }

        // TFLite reports scale=0 for non-quantized tensors. Keep this helper defined
        // in raw integer units in that case; callers restrict it to INT8/UINT8 tensors.
        val effectiveScale = if (scale.isFinite() && scale > 0f) scale.toDouble() else 1.0
        var dot = 0.0
        var referenceNorm = 0.0
        var candidateNorm = 0.0
        var absoluteErrorSum = 0.0
        var maxAbsoluteError = 0.0
        var exactMatches = 0
        var referenceTopIndex = 0
        var candidateTopIndex = 0
        var referenceTopValue = Double.NEGATIVE_INFINITY
        var candidateTopValue = Double.NEGATIVE_INFINITY

        for (index in reference.indices) {
            val referenceRaw = if (signed) reference[index].toInt() else reference[index].toInt() and 0xff
            val candidateRaw = if (signed) candidate[index].toInt() else candidate[index].toInt() and 0xff
            val referenceValue = (referenceRaw - zeroPoint) * effectiveScale
            val candidateValue = (candidateRaw - zeroPoint) * effectiveScale

            dot += referenceValue * candidateValue
            referenceNorm += referenceValue * referenceValue
            candidateNorm += candidateValue * candidateValue
            val absoluteError = kotlin.math.abs(referenceValue - candidateValue)
            absoluteErrorSum += absoluteError
            if (absoluteError > maxAbsoluteError) maxAbsoluteError = absoluteError
            if (reference[index] == candidate[index]) exactMatches++

            if (referenceValue > referenceTopValue) {
                referenceTopValue = referenceValue
                referenceTopIndex = index
            }
            if (candidateValue > candidateTopValue) {
                candidateTopValue = candidateValue
                candidateTopIndex = index
            }
        }

        val denominator = sqrt(referenceNorm * candidateNorm)
        val cosine = when {
            denominator > 0.0 -> (dot / denominator).coerceIn(-1.0, 1.0)
            referenceNorm == 0.0 && candidateNorm == 0.0 -> 1.0
            else -> 0.0
        }

        return TensorComparisonResult(
            cosineSimilarity = cosine,
            meanAbsoluteError = absoluteErrorSum / reference.size,
            maxAbsoluteError = maxAbsoluteError,
            exactMatchPercent = exactMatches * 100.0 / reference.size,
            referenceTopIndex = referenceTopIndex,
            candidateTopIndex = candidateTopIndex,
            top1Agreement = referenceTopIndex == candidateTopIndex
        )
    }
}
