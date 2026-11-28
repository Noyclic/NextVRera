package com.noyclic.xrclientprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenXrFailureCatalogTest {
    @Test
    fun runtimeUnavailableIsBlockingAndPointsToRuntimeBroker() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_RUNTIME_UNAVAILABLE (-51)",
            "xrCreateInstance"
        )
        assertNotNull(result)
        assertEquals("XR_ERROR_RUNTIME_UNAVAILABLE", result?.code)
        assertEquals(-51, result?.numericValue)
        assertEquals(ExternalOpenXrFailureCatalog.Impact.BLOCKING, result?.impact)
        assertTrue(result?.checks.orEmpty().any { it.contains("RuntimeBroker") })
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION,
            result?.stageHint
        )
    }

    @Test
    fun numericValueIsPreservedButNeverUsedAsTheSymbolicLookupKey() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_RUNTIME_FAILURE (-51)",
            "xrCreateInstance"
        )
        assertEquals("XR_ERROR_RUNTIME_FAILURE", result?.code)
        assertEquals(-51, result?.numericValue)
        assertEquals(
            ExternalOpenXrFailureCatalog.Category.INITIALIZATION,
            result?.category
        )
        assertFalse(result?.summary.orEmpty().contains("active OpenXR runtime"))
    }

    @Test
    fun unsupportedApiVersionIsNotConfusedWithExtensionMissing() {
        val api = ExternalOpenXrFailureCatalog.diagnose("XR_ERROR_API_VERSION_UNSUPPORTED (-4)")
        val extension = ExternalOpenXrFailureCatalog.diagnose("XR_ERROR_EXTENSION_NOT_PRESENT (-9)")
        assertEquals(
            ExternalOpenXrFailureCatalog.Category.API_COMPATIBILITY,
            api?.category
        )
        assertEquals(
            ExternalOpenXrFailureCatalog.Category.EXTENSION_SUPPORT,
            extension?.category
        )
        assertNotEquals(api?.code, extension?.code)
    }

    @Test
    fun graphicsFailureAddsGraphicsSpecificChecks() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_GRAPHICS_DEVICE_INVALID",
            "external_egl_context"
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING,
            result?.stageHint
        )
        assertTrue(result?.checks.orEmpty().any { it.contains("EGL display/context") })
    }

    @Test
    fun frameOrderFailureWarnsAgainstTightRetry() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_CALL_ORDER_INVALID",
            "external_xrEndFrame(projection)"
        )
        assertEquals(
            ExternalOpenXrFailureCatalog.Category.FRAME_ORDER,
            result?.category
        )
        assertTrue(result?.retryAdvice.orEmpty().contains("will not help"))
        assertTrue(result?.actions.orEmpty().any { it.contains("frame loop") })
    }

    @Test
    fun sessionLostDoesNotRecommendRetryingTheSameSession() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_SESSION_LOST",
            "external_xrWaitFrame"
        )
        assertTrue(result?.retryAdvice.orEmpty().contains("same lost session"))
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP,
            result?.stageHint
        )
    }

    @Test
    fun swapchainFormatFailureExplainsFormatEnumeration() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED",
            "external_xrCreateSwapchain"
        )
        assertEquals(
            ExternalOpenXrFailureCatalog.Category.SWAPCHAIN,
            result?.category
        )
        assertTrue(result?.checks.orEmpty().any { it.contains("formats enumerated") })
    }

    @Test
    fun unknownSymbolicResultIsPreservedWithoutInventedMeaning() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_VENDOR_SPECIFIC_FUTURE_RESULT (-70001)",
            "xrGetSystem(HMD)"
        )
        assertNotNull(result)
        assertFalse(result!!.known)
        assertEquals("XR_ERROR_VENDOR_SPECIFIC_FUTURE_RESULT", result.code)
        assertEquals(-70001, result.numericValue)
        assertTrue(result.summary.contains("not in the local diagnostic catalogue"))
        assertTrue(result.actions.any { it.contains("guessed error meaning") })
    }

    @Test
    fun nonOpenXrFailureTextRemainsUnclassified() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "provider_query=FAIL; permission=DENIED",
            "EndXR input IPC"
        )
        assertNotNull(result)
        assertNull(result?.code)
        assertEquals(
            ExternalOpenXrFailureCatalog.Category.INPUT,
            result?.category
        )
        assertEquals(
            ExternalOpenXrFailureCatalog.Impact.HIGH,
            result?.impact
        )
    }

    @Test
    fun successfulResultsDoNotCreateFailureDiagnosis() {
        assertNull(ExternalOpenXrFailureCatalog.diagnose("XR_SUCCESS"))
        assertNull(ExternalOpenXrFailureCatalog.diagnose("PASS"))
        assertNull(ExternalOpenXrFailureCatalog.diagnose(null))
        assertNull(ExternalOpenXrFailureCatalog.diagnose(""))
    }

    @Test
    fun knownCodesAreStableAndNonEmpty() {
        val codes = ExternalOpenXrFailureCatalog.knownCodes()
        assertTrue(codes.size >= 25)
        assertTrue(codes.contains("XR_ERROR_RUNTIME_UNAVAILABLE"))
        assertTrue(codes.contains("XR_ERROR_GRAPHICS_DEVICE_INVALID"))
        assertTrue(codes.contains("XR_ERROR_CALL_ORDER_INVALID"))
    }

    @Test
    fun resultInputIsBoundedBeforeDisplay() {
        val huge = "XR_ERROR_RUNTIME_FAILURE " + "x".repeat(10_000)
        val result = ExternalOpenXrFailureCatalog.diagnose(huge)
        assertNotNull(result)
        assertTrue(result!!.rawResult.length <= 240)
    }

    @Test
    fun handTrackingAdviceIsKeptSeparateFromStereoRendering() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_EXTENSION_NOT_PRESENT",
            "external_xrCreateHandTrackerEXT_left"
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING,
            result?.stageHint
        )
        assertTrue(result?.actions.orEmpty().any { it.contains("basic stereo rendering") })
    }

    @Test
    fun brokerDiagnosisPrioritizesRuntimeSelection() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_RUNTIME_UNAVAILABLE",
            "RuntimeBroker query"
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER,
            result?.stageHint
        )
        assertTrue(result?.actions?.firstOrNull().orEmpty().contains("RuntimeBroker"))
    }

    @Test
    fun diagnosisDisplayContainsRetryGuidanceAndStage() {
        val result = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_FORM_FACTOR_UNAVAILABLE",
            "xrGetSystem(HMD)"
        )
        val text = result?.toDisplayText().orEmpty()
        assertTrue(text.contains("retry="))
        assertTrue(text.contains("stage_hint=HMD_SYSTEM"))
        assertTrue(text.contains("likely_cause="))
    }

    private fun assertNotEquals(expected: Any?, actual: Any?) {
        assertFalse("Expected values to differ, both were " + expected, expected == actual)
    }
}
