package com.noyclic.xrclientprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenXrReportIntegrityTest {
    private fun service(
        installable: String = "provider found; no active runtime selected for arm64-v8a",
        system: String = "provider not installed or not visible",
        input: String = "provider_permission=GRANTED; provider_query=PASS; provider=com.noyclic.questphonexr"
    ) = """
        EndXR service visible: YES
        EndXR service exported: YES
        Runtime library exists: YES
        Installable RuntimeBroker: $installable
        System RuntimeBroker: $system
        EndXR input IPC: $input
    """.trimIndent()

    private fun loader(): String = """
        xrInitializeLoaderKHR call=XR_SUCCESS
        xrEnumerateInstanceExtensionProperties(count)=XR_SUCCESS
        xrEnumerateInstanceExtensionProperties(list)=XR_SUCCESS
        xrCreateInstance=XR_SUCCESS
        runtime_instance_created=YES
        xrGetSystem(HMD)=XR_SUCCESS
        external_xrCreateSession=XR_SUCCESS
        external_xrCreateSwapchain=XR_SUCCESS
        external_xrEnumerateSwapchainImages=XR_SUCCESS
        external_xrWaitFrame=XR_SUCCESS
        external_xrBeginFrame=XR_SUCCESS
        external_xrAcquireSwapchainImage=XR_SUCCESS
        external_xrWaitSwapchainImage=XR_SUCCESS
        external_xrReleaseSwapchainImage=XR_SUCCESS
        external_xrEndFrame(projection)=XR_SUCCESS
        external_projection_layer_submission=PASS
        external_projection_swapchain_image_verification=PASS
        runtime_advertises_XR_EXT_hand_tracking=YES
        external_xrCreateHandTrackerEXT_left=XR_SUCCESS
        external_xrCreateHandTrackerEXT_right=XR_SUCCESS
        external_xrLocateHandJointsEXT_left=XR_SUCCESS
        external_xrLocateHandJointsEXT_right=XR_SUCCESS
        xrDestroyInstance=XR_SUCCESS
        external_frame_lifecycle=PASS
    """.trimIndent()

    private fun codes(report: ExternalOpenXrReportIntegrity.Report): Set<String> =
        report.findings.map { it.code }.toSet()

    @Test
    fun coherentReportHasNoIntegrityFindings() {
        val report = ExternalOpenXrReportIntegrity.analyze(service(), loader())
        assertTrue(report.findings.isEmpty())
        assertTrue(report.isConsistent)
        assertEquals(0, report.blockingCount)
    }

    @Test
    fun instanceCreatedYesAfterCreateErrorIsBlocking() {
        val raw = loader()
            .replace("xrCreateInstance=XR_SUCCESS", "xrCreateInstance=XR_ERROR_RUNTIME_UNAVAILABLE (-51)")
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("INSTANCE_CREATED_AFTER_CREATE_FAILURE"))
        assertEquals(1, report.blockingCount)
        assertFalse(report.isConsistent)
    }

    @Test
    fun instanceCreatedNoAfterCreateSuccessIsBlocking() {
        val raw = loader().replace("runtime_instance_created=YES", "runtime_instance_created=NO")
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("INSTANCE_NOT_CREATED_AFTER_CREATE_SUCCESS"))
    }

    @Test
    fun successfulSessionAfterExplicitlyFailedInstanceIsRejected() {
        val raw = loader()
            .replace("runtime_instance_created=YES", "runtime_instance_created=NO")
            .replace("xrCreateInstance=XR_SUCCESS", "xrCreateInstance=XR_ERROR_INITIALIZATION_FAILED (-6)")
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("SESSION_SUCCESS_WITHOUT_INSTANCE"))
    }

    @Test
    fun successfulSessionWithMissingInstanceMarkerIsOnlyWarning() {
        val raw = loader().lineSequence()
            .filterNot { it.startsWith("runtime_instance_created=") }
            .joinToString("\n")
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("SESSION_WITHOUT_INSTANCE_EVIDENCE"))
        assertEquals(0, report.blockingCount)
    }

    @Test
    fun swapchainSuccessAfterSessionFailureIsBlocking() {
        val raw = loader().replace(
            "external_xrCreateSession=XR_SUCCESS",
            "external_xrCreateSession=XR_ERROR_SESSION_LOST (-17)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("SWAPCHAIN_SUCCESS_AFTER_SESSION_FAILURE"))
    }

    @Test
    fun imageEnumerationSuccessAfterSwapchainFailureIsHighSeverity() {
        val raw = loader().replace(
            "external_xrCreateSwapchain=XR_SUCCESS",
            "external_xrCreateSwapchain=XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED (-26)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("SWAPCHAIN_IMAGES_SUCCESS_AFTER_CREATE_FAILURE"))
        assertEquals(1, report.highCount)
    }

    @Test
    fun beginFrameAfterWaitFrameErrorIsBlocking() {
        val raw = loader().replace(
            "external_xrWaitFrame=XR_SUCCESS",
            "external_xrWaitFrame=XR_ERROR_SESSION_NOT_RUNNING (-16)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("BEGIN_FRAME_AFTER_WAIT_FRAME_FAILURE"))
    }

    @Test
    fun endFrameAfterBeginFrameErrorIsBlocking() {
        val raw = loader().replace(
            "external_xrBeginFrame=XR_SUCCESS",
            "external_xrBeginFrame=XR_ERROR_CALL_ORDER_INVALID (-37)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("END_FRAME_AFTER_BEGIN_FRAME_FAILURE"))
    }

    @Test
    fun frameCallsWithMissingPredecessorEvidenceAreWarnings() {
        val raw = loader().lineSequence()
            .filterNot { it.startsWith("external_xrWaitFrame=") }
            .joinToString("\n")
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("BEGIN_FRAME_WITHOUT_WAIT_EVIDENCE"))
        assertEquals(0, report.blockingCount)
    }

    @Test
    fun waitImageAfterAcquireErrorIsBlocking() {
        val raw = loader().replace(
            "external_xrAcquireSwapchainImage=XR_SUCCESS",
            "external_xrAcquireSwapchainImage=XR_ERROR_CALL_ORDER_INVALID (-37)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("WAIT_IMAGE_AFTER_ACQUIRE_FAILURE"))
    }

    @Test
    fun releaseAfterWaitErrorIsHighSeverity() {
        val raw = loader().replace(
            "external_xrWaitSwapchainImage=XR_SUCCESS",
            "external_xrWaitSwapchainImage=XR_ERROR_SESSION_LOST (-17)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("RELEASE_IMAGE_AFTER_WAIT_FAILURE"))
    }

    @Test
    fun projectionPassWithEndFrameErrorIsBlocking() {
        val raw = loader().replace(
            "external_xrEndFrame(projection)=XR_SUCCESS",
            "external_xrEndFrame(projection)=XR_ERROR_LAYER_INVALID (-23)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("PROJECTION_PASS_WITH_END_FRAME_ERROR"))
    }

    @Test
    fun readbackPassWithoutProjectionPassIsBlocking() {
        val raw = loader().replace(
            "external_projection_layer_submission=PASS",
            "external_projection_layer_submission=REJECTED_BY_RUNTIME"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("READBACK_PASS_WITHOUT_ACCEPTED_PROJECTION"))
    }

    @Test
    fun projectionRejectedWithSuccessfulEndFrameIsReported() {
        val raw = loader().replace(
            "external_projection_layer_submission=PASS",
            "external_projection_layer_submission=REJECTED_BY_RUNTIME"
        ).replace(
            "external_projection_swapchain_image_verification=PASS",
            "external_projection_swapchain_image_verification=FAIL"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("PROJECTION_REJECTED_WITH_END_FRAME_SUCCESS"))
    }

    @Test
    fun handTrackerSuccessWhenExtensionIsNotAdvertisedIsHighSeverity() {
        val raw = loader().replace(
            "runtime_advertises_XR_EXT_hand_tracking=YES",
            "runtime_advertises_XR_EXT_hand_tracking=NO"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("HAND_TRACKING_CALL_SUCCESS_WHEN_NOT_ADVERTISED"))
    }

    @Test
    fun handLocateSuccessAfterTrackerCreationErrorIsBlocking() {
        val raw = loader().replace(
            "external_xrCreateHandTrackerEXT_left=XR_SUCCESS",
            "external_xrCreateHandTrackerEXT_left=XR_ERROR_EXTENSION_NOT_PRESENT (-9)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("LEFT_HAND_LOCATE_AFTER_CREATE_FAILURE"))
    }

    @Test
    fun conflictingDuplicateCreateInstanceObservationIsDetected() {
        val raw = loader() + "\nxrCreateInstance=XR_ERROR_RUNTIME_UNAVAILABLE (-51)"
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("CONFLICTING_DUPLICATE_LOADER"))
    }

    @Test
    fun repeatedEquivalentObservationIsNotTreatedAsConflict() {
        val raw = loader() + "\nxrCreateInstance=XR_SUCCESS"
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertFalse(codes(report).contains("CONFLICTING_DUPLICATE_LOADER"))
        assertTrue(codes(report).contains("CHANGING_DUPLICATE_LOADER").not())
    }

    @Test
    fun nonConflictingChangedDuplicateIsAWarning() {
        val raw = loader() + "\nexternal_frame_lifecycle=PARTIAL"
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("CHANGING_DUPLICATE_LOADER"))
        assertEquals(0, report.blockingCount)
    }

    @Test
    fun brokerProvidersDisagreementIsNotClaimedAsASelectionFailure() {
        val report = ExternalOpenXrReportIntegrity.analyze(
            service(
                installable = "active package=org.example.installable; exists=true",
                system = "active package=org.example.system; exists=true"
            ),
            loader()
        )
        assertTrue(codes(report).contains("BROKER_PROVIDERS_DISAGREE"))
        assertEquals(0, report.blockingCount)
    }

    @Test
    fun inputQueryPassAndPermissionDeniedIsDetected() {
        val report = ExternalOpenXrReportIntegrity.analyze(
            service(input = "provider_permission=DENIED; provider_query=PASS"),
            loader()
        )
        assertTrue(codes(report).contains("INPUT_QUERY_PASS_WITH_PERMISSION_DENIED"))
    }

    @Test
    fun frameSummarySkippedWhileCallsSucceededIsDetected() {
        val raw = loader().replace(
            "external_frame_lifecycle=PASS",
            "external_frame_lifecycle=SKIPPED"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertTrue(codes(report).contains("FRAME_SUMMARY_SKIPPED_WITH_SUCCESSFUL_CALL"))
    }

    @Test
    fun unknownInputIsHandledWithoutThrowing() {
        val report = ExternalOpenXrReportIntegrity.analyze("unstructured input", "another unstructured input")
        assertTrue(report.findings.isEmpty())
        assertTrue(report.toDisplayText().contains("NO_HIGH_CONFLICTS_FOUND"))
        assertTrue(report.toJson().contains("\"findings\":[]"))
    }

    @Test
    fun reportLengthIsBoundedAndJsonEscapesUntrustedText() {
        val huge = loader() + "\nexternal_frame_lifecycle=" + "x".repeat(300_000)
        val report = ExternalOpenXrReportIntegrity.analyze(service(), huge)
        val json = report.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(report.toDisplayText().length < 20_000)
    }

    @Test
    fun resultCountsAreDerivedFromFindings() {
        val raw = loader().replace(
            "external_xrWaitFrame=XR_SUCCESS",
            "external_xrWaitFrame=XR_ERROR_SESSION_NOT_RUNNING (-16)"
        )
        val report = ExternalOpenXrReportIntegrity.analyze(service(), raw)
        assertEquals(report.findings.count { it.severity == ExternalOpenXrReportIntegrity.Severity.BLOCKING }, report.blockingCount)
        assertEquals(report.findings.count { it.severity == ExternalOpenXrReportIntegrity.Severity.HIGH }, report.highCount)
        assertEquals(report.findings.count { it.severity == ExternalOpenXrReportIntegrity.Severity.WARNING }, report.warningCount)
    }
}
