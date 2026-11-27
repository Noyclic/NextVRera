package com.noyclic.literthtpprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtpModelContractValidatorTest {
    private fun tensor(
        index: Int,
        shape: List<Int>,
        type: String = "FLOAT32"
    ) = TensorInfo(
        index = index,
        name = "tensor_$index",
        shape = shape,
        shapeSignature = shape,
        type = type,
        bytes = shape.fold(4) { total, dimension -> total * dimension },
        elementCount = shape.fold(1) { total, dimension -> total * dimension },
        quantizationScale = 0f,
        quantizationZeroPoint = 0
    )

    private fun report(input: TensorInfo, vararg outputs: TensorInfo) = HtpReport(
        model = "test.tflite",
        input = input,
        outputs = outputs.toList(),
        warmupMs = 1.0,
        medianMs = 1.0,
        p95Ms = 2.0
    )

    @Test
    fun acceptsPalmDetectorWithNchwInputAndMatchingAnchors() {
        val result = HtpModelContractValidator.validate(
            HtpModelKind.PALM_DETECTOR,
            report(
                tensor(0, listOf(1, 3, 256, 256)),
                tensor(0, listOf(1, 2944, 18)),
                tensor(1, listOf(1, 2944, 1))
            )
        )

        assertTrue(result.passed)
        assertTrue(result.inputLayout == "NCHW")
    }

    @Test
    fun acceptsPalmDetectorWithNhwcInputAndPairedCoordinateLayout() {
        val result = HtpModelContractValidator.validate(
            HtpModelKind.PALM_DETECTOR,
            report(
                tensor(0, listOf(1, 256, 256, 3)),
                tensor(0, listOf(1, 896, 9, 2)),
                tensor(1, listOf(1, 896))
            )
        )

        assertTrue(result.passed)
        assertTrue(result.inputLayout == "NHWC")
    }

    @Test
    fun rejectsPalmDetectorWhenScoreAnchorCountDoesNotMatchCoordinates() {
        val result = HtpModelContractValidator.validate(
            HtpModelKind.PALM_DETECTOR,
            report(
                tensor(0, listOf(1, 3, 256, 256)),
                tensor(0, listOf(1, 2944, 18)),
                tensor(1, listOf(1, 896, 1))
            )
        )

        assertFalse(result.passed)
        assertTrue(result.checks.any { it.name == "Detector anchor alignment" && !it.passed })
    }

    @Test
    fun rejectsWrongPalmInputDimensionsAndDataType() {
        val result = HtpModelContractValidator.validate(
            HtpModelKind.PALM_DETECTOR,
            report(
                tensor(0, listOf(1, 3, 224, 224), "INT8"),
                tensor(0, listOf(1, 2944, 18)),
                tensor(1, listOf(1, 2944, 1))
            )
        )

        assertFalse(result.passed)
        assertTrue(result.checks.any { it.name == "Input tensor" && !it.passed })
        assertTrue(result.checks.any { it.name == "Input element type" && !it.passed })
    }

    @Test
    fun acceptsHandLandmarkPresenceHandednessAndTwentyOnePoints() {
        val result = HtpModelContractValidator.validate(
            HtpModelKind.HAND_LANDMARK,
            report(
                tensor(0, listOf(1, 3, 256, 256)),
                tensor(0, listOf(1, 1)),
                tensor(1, listOf(1)),
                tensor(2, listOf(1, 21, 3))
            )
        )

        assertTrue(result.passed)
    }

    @Test
    fun rejectsHandLandmarkWithWrongPointCountOrOutputCount() {
        val badLandmarks = HtpModelContractValidator.validate(
            HtpModelKind.HAND_LANDMARK,
            report(
                tensor(0, listOf(1, 3, 256, 256)),
                tensor(0, listOf(1, 1)),
                tensor(1, listOf(1, 1)),
                tensor(2, listOf(1, 20, 3))
            )
        )
        val missingOutput = HtpModelContractValidator.validate(
            HtpModelKind.HAND_LANDMARK,
            report(
                tensor(0, listOf(1, 3, 256, 256)),
                tensor(0, listOf(1, 1)),
                tensor(1, listOf(1, 1))
            )
        )

        assertFalse(badLandmarks.passed)
        assertFalse(missingOutput.passed)
    }
}
