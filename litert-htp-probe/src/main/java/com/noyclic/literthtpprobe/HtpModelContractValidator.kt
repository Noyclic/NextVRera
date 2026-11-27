package com.noyclic.literthtpprobe

enum class HtpModelKind(val displayName: String) {
    PALM_DETECTOR("Palm detector"),
    HAND_LANDMARK("Hand landmark")
}

data class HtpContractCheck(
    val name: String,
    val passed: Boolean,
    val detail: String
)

data class HtpContractResult(
    val modelKind: HtpModelKind,
    val inputLayout: String?,
    val checks: List<HtpContractCheck>
) {
    val passed: Boolean
        get() = checks.isNotEmpty() && checks.all { it.passed }

    fun format(): String = buildString {
        appendLine("MODEL CONTRACT: " + if (passed) "PASS" else "FAIL")
        inputLayout?.let { appendLine("Input layout: $it") }
        checks.forEach { check ->
            append(if (check.passed) "[PASS] " else "[FAIL] ")
            append(check.name).append(": ").appendLine(check.detail)
        }
        appendLine("Contract validation checks model metadata only; it does not prove landmark accuracy, full HTP operator placement, or real-time tracking.")
    }
}

/**
 * Validates the public Qualcomm MediaPipe-Hand model component I/O shapes.
 *
 * This intentionally validates shapes rather than depending on generated tensor names,
 * which may be rewritten by the TFLite exporter. Output order follows the component
 * output specs: detector coords/scores; landmark presence/hand-side/landmarks.
 */
object HtpModelContractValidator {
    private val scalarShapes = setOf(listOf(1), listOf(1, 1))

    fun validate(kind: HtpModelKind, report: HtpReport): HtpContractResult {
        val checks = mutableListOf<HtpContractCheck>()
        val inputShape = report.input.shape
        val layout = when (inputShape) {
            listOf(1, 3, 256, 256) -> "NCHW"
            listOf(1, 256, 256, 3) -> "NHWC"
            else -> null
        }
        checks += HtpContractCheck(
            "Input tensor",
            layout != null,
            if (layout != null) {
                "shape=$inputShape, 256×256 RGB, batch=1 ($layout)"
            } else {
                "Expected [1,3,256,256] NCHW or [1,256,256,3] NHWC; got $inputShape"
            }
        )
        checks += HtpContractCheck(
            "Input element type",
            report.input.type == "FLOAT32",
            "expected FLOAT32 for this strict QNN probe path; got ${report.input.type}"
        )
        checks += HtpContractCheck(
            "Input metadata",
            report.input.elementCount > 0 && report.input.bytes > 0,
            "elements=${report.input.elementCount}, bytes=${report.input.bytes}, shape_signature=${report.input.shapeSignature}"
        )

        when (kind) {
            HtpModelKind.PALM_DETECTOR -> validatePalmDetector(report.outputs, checks)
            HtpModelKind.HAND_LANDMARK -> validateHandLandmark(report.outputs, checks)
        }
        return HtpContractResult(kind, layout, checks)
    }

    private fun validatePalmDetector(
        outputs: List<TensorInfo>,
        checks: MutableList<HtpContractCheck>
    ) {
        checks += HtpContractCheck(
            "Output count",
            outputs.size == 2,
            "Qualcomm palm detector exports coordinates and scores as two tensors; got ${outputs.size}"
        )
        if (outputs.size != 2) return

        val coords = outputs[0]
        val scores = outputs[1]
        val anchorCount = detectorAnchorCount(coords.shape)
        checks += HtpContractCheck(
            "Detector coordinates",
            anchorCount != null,
            "expected [1,N,18] or [1,N,9,2] with N>0 (box plus seven 2D keypoints); got ${coords.shape}"
        )
        val scoreAnchorCount = detectorScoreAnchorCount(scores.shape)
        checks += HtpContractCheck(
            "Detector scores",
            scoreAnchorCount != null,
            "expected [1,N] or [1,N,1] with N>0; got ${scores.shape}"
        )
        checks += HtpContractCheck(
            "Detector anchor alignment",
            anchorCount != null && scoreAnchorCount != null && anchorCount == scoreAnchorCount,
            "coordinate anchors=${anchorCount ?: "invalid"}, score anchors=${scoreAnchorCount ?: "invalid"}"
        )
    }

    private fun validateHandLandmark(
        outputs: List<TensorInfo>,
        checks: MutableList<HtpContractCheck>
    ) {
        checks += HtpContractCheck(
            "Output count",
            outputs.size == 3,
            "Qualcomm hand landmark exports presence, handedness and landmarks as three tensors; got ${outputs.size}"
        )
        if (outputs.size != 3) return

        checks += HtpContractCheck(
            "Hand presence tensor",
            outputs[0].shape in scalarShapes,
            "expected scalar [1] or [1,1]; got ${outputs[0].shape}"
        )
        checks += HtpContractCheck(
            "Handedness tensor",
            outputs[1].shape in scalarShapes,
            "expected scalar [1] or [1,1]; got ${outputs[1].shape}"
        )
        checks += HtpContractCheck(
            "21-point landmark tensor",
            outputs[2].shape == listOf(1, 21, 3),
            "expected [1,21,3] (x,y,z for each landmark); got ${outputs[2].shape}"
        )
    }

    private fun detectorAnchorCount(shape: List<Int>): Int? = when {
        shape.size == 3 && shape[0] == 1 && shape[1] > 0 && shape[2] == 18 -> shape[1]
        shape.size == 4 && shape[0] == 1 && shape[1] > 0 &&
            shape[2] == 9 && shape[3] == 2 -> shape[1]
        else -> null
    }

    private fun detectorScoreAnchorCount(shape: List<Int>): Int? = when {
        shape.size == 2 && shape[0] == 1 && shape[1] > 0 -> shape[1]
        shape.size == 3 && shape[0] == 1 && shape[1] > 0 && shape[2] == 1 -> shape[1]
        else -> null
    }
}
