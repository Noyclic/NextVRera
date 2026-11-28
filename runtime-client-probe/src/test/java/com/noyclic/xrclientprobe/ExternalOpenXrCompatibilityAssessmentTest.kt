package com.noyclic.xrclientprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenXrCompatibilityAssessmentTest {
    private fun service(
        installable: String = "provider found; no active runtime selected for arm64-v8a",
        system: String = "provider not installed or not visible",
        visible: String = "YES",
        exported: String = "YES",
        library: String = "YES",
        input: String = "provider_permission=GRANTED; provider_query=PASS; provider=com.noyclic.questphonexr"
    ) = """
        QuestPhoneXR external-process discovery probe
        Client package: com.noyclic.xrclientprobe
        EndXR service visible: $visible
        EndXR service exported: $exported
        Runtime library exists: $library
        Installable RuntimeBroker: $installable
        System RuntimeBroker: $system
        EndXR input IPC: $input
    """.trimIndent()

    private fun loader(
        instance: String = "XR_SUCCESS",
        created: String = if (instance == "XR_SUCCESS") "YES" else "NO",
        includeSession: Boolean = true,
        projection: String = "PASS",
        handTracking: String = "YES"
    ): String = buildString {
        appendLine("Khronos OpenXR Android Loader probe")
        appendLine("xrInitializeLoaderKHR call=XR_SUCCESS")
        val runtimeUnavailable = instance.startsWith("XR_ERROR_RUNTIME_UNAVAILABLE")
        appendLine(
            "xrEnumerateInstanceExtensionProperties(count)=" +
                if (runtimeUnavailable) "XR_ERROR_RUNTIME_UNAVAILABLE (-51)" else "XR_SUCCESS"
        )
        if (!runtimeUnavailable) {
            appendLine("xrEnumerateInstanceExtensionProperties(list)=XR_SUCCESS")
            appendLine("runtime_advertises_XR_EXT_hand_tracking=$handTracking")
        }
        appendLine("xrCreateInstance=$instance")
        appendLine("runtime_instance_created=$created")
        if (created == "YES") {
            appendLine("xrGetSystem(HMD)=XR_SUCCESS")
            appendLine("primary_stereo_supported=YES")
        }
        if (includeSession && created == "YES") {
            appendLine("external_egl_context=PASS")
            appendLine("xrGetOpenGLESGraphicsRequirementsKHR=XR_SUCCESS")
            appendLine("external_xrCreateSession=XR_SUCCESS")
            appendLine("external_xrCreateSwapchain=XR_SUCCESS")
            appendLine("external_xrEnumerateSwapchainImages=XR_SUCCESS")
            appendLine("external_xrWaitFrame=XR_SUCCESS")
            appendLine("external_xrBeginFrame=XR_SUCCESS")
            appendLine("external_xrEndFrame(projection)=XR_SUCCESS")
            appendLine("external_projection_layer_submission=$projection")
            appendLine("external_projection_swapchain_image_verification=$projection")
            appendLine("external_xrDestroySession=XR_SUCCESS")
            appendLine("external_xrLocateHandJointsEXT_left=XR_SUCCESS active=NO position_valid=0 orientation_valid=0 tracked=0")
            appendLine("external_xrLocateHandJointsEXT_right=XR_SUCCESS active=NO position_valid=0 orientation_valid=0 tracked=0")
            appendLine("external_xrCreateHandTrackerEXT_left=XR_SUCCESS")
            appendLine("external_xrCreateHandTrackerEXT_right=XR_SUCCESS")
        }
        if (created == "YES") appendLine("xrDestroyInstance=XR_SUCCESS")
    }

    @Test
    fun fullExternalPathIsReportedStageByStage() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(installable = "active package=com.noyclic.questphonexr; library=/data/app/libendxr_openxr.so; exists=true"),
            loader()
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY).status
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER).status
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION).status
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION).status
        )
        assertEquals("com.noyclic.questphonexr", result.selectedRuntimePackage)
        assertNull(result.firstFailure)
    }

    @Test
    fun runtimeUnavailableIsBlockedAndDoesNotInventInstanceSuccess() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(),
            loader(instance = "XR_ERROR_RUNTIME_UNAVAILABLE (-51)", created = "NO", includeSession = false)
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.BLOCKED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION).status
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.NOT_RUN,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN).status
        )
        assertEquals("xrCreateInstance", result.firstFailure?.key)
        assertEquals(ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION, result.firstFailure?.stage)
    }

    @Test
    fun serviceDiscoveryFailureIsNotHiddenByLoaderResult() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(visible = "NO"),
            loader()
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.FAILED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY).status
        )
    }

    @Test
    fun nonEndXrRuntimeIsPartialNotEndXrSuccess() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(installable = "active package=org.example.other; library=/vendor/lib/libother.so; exists=true"),
            loader()
        )
        assertEquals("org.example.other", result.selectedRuntimePackage)
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.PARTIAL,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER).status
        )
        assertTrue(result.warnings.any { it.contains("rather than EndXR") })
    }

    @Test
    fun missingBrokerProvidersAreBlockedButDoNotFailLoaderInitialization() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(
                installable = "provider not installed or not visible",
                system = "provider not installed or not visible"
            ),
            loader(instance = "XR_ERROR_RUNTIME_UNAVAILABLE (-51)", created = "NO", includeSession = false)
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.BLOCKED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER).status
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION).status
        )
    }

    @Test
    fun brokerQueryFailureIsDifferentFromMissingProvider() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(installable = "provider found (org.example.broker); query failed: SecurityException"),
            loader(instance = "XR_ERROR_RUNTIME_UNAVAILABLE (-51)", created = "NO", includeSession = false)
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.FAILED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER).status
        )
    }

    @Test
    fun projectionRejectedIsFailedEvenWhenFrameEndReturnedAnError() {
        val report = loader(projection = "REJECTED_BY_RUNTIME")
            .replace("external_xrEndFrame(projection)=XR_SUCCESS", "external_xrEndFrame(projection)=XR_ERROR_LAYER_INVALID (-31)")
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.FAILED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION).status
        )
        assertEquals("external_xrEndFrame(projection)", result.firstFailure?.key)
    }

    @Test
    fun successfulProjectionWithoutReadbackIsOnlyPartial() {
        val report = loader().lineSequence()
            .filterNot { it.startsWith("external_projection_swapchain_image_verification=") }
            .joinToString("\n")
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.PARTIAL,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION).status
        )
    }

    @Test
    fun handTrackingNotAdvertisedIsBlockedNotFailed() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(),
            loader(handTracking = "NO")
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.BLOCKED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING).status
        )
    }

    @Test
    fun absentHandTrackingEvidenceIsNotRunWhenInstanceWasNotCreated() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(),
            loader(instance = "XR_ERROR_RUNTIME_UNAVAILABLE (-51)", created = "NO", includeSession = false)
                .lineSequence()
                .filterNot { it.startsWith("runtime_advertises_XR_EXT_hand_tracking=") }
                .joinToString("\n")
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.NOT_RUN,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING).status
        )
    }

    @Test
    fun deniedInputPermissionIsASeparateFailure() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(input = "provider_permission=DENIED; package is not signed with EndXR"),
            loader()
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.FAILED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE).status
        )
    }

    @Test
    fun cameraEstimatedHandPosesAreNotPromotedToMetricTrackingClaims() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), loader())
        val text = result.toDisplayText()
        assertTrue(text.contains("does not certify OpenXR conformance"))
        assertTrue(text.contains("HAND_TRACKING"))
        assertFalse(text.contains("6DoF tracking verified"))
    }

    @Test
    fun missingEvidenceRemainsUnknown() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze("", "")
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY).status
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION).status
        )
        assertNotNull(result.toDisplayText())
    }

    @Test
    fun jsonEscapesQuotesAndNewlinesInUntrustedEvidence() {
        val report = loader().replace(
            "external_egl_context=PASS",
            "external_egl_context=PASS; detail=quote \" and newline\\nnext"
        )
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        val json = result.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\n"))
    }
    @Test
    fun firstFailureIsSelectedInOpenXrCallOrder() {
        val report = loader()
            .replace("xrGetSystem(HMD)=XR_SUCCESS", "xrGetSystem(HMD)=XR_ERROR_FORM_FACTOR_UNAVAILABLE (-34)")
            .replace("external_xrCreateSession=XR_SUCCESS", "external_xrCreateSession=XR_ERROR_SESSION_LOST (-17)")
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        assertEquals("xrGetSystem(HMD)", result.firstFailure?.key)
        assertEquals(ExternalOpenXrCompatibilityAssessment.StageId.HMD_SYSTEM, result.firstFailure?.stage)
    }

    @Test
    fun parserKeepsValuesContainingEqualsSigns() {
        val report = loader().replace(
            "external_egl_context=PASS",
            "external_egl_context=PASS; config=rgba=8888"
        )
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING).status
        )
    }

    @Test
    fun duplicateKeysUseLatestObservation() {
        val report = loader() + "\nxrInitializeLoaderKHR call=XR_ERROR_INITIALIZATION_FAILED (-6)"
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.FAILED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION).status
        )
    }

    @Test
    fun selectedRuntimePrefersInstallableBrokerOverSystemBroker() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(
                installable = "active package=org.example.installable; library=/a.so; exists=true",
                system = "active package=com.noyclic.questphonexr; library=/b.so; exists=true"
            ),
            loader()
        )
        assertEquals("org.example.installable", result.selectedRuntimePackage)
    }

    @Test
    fun successfulInstanceWithoutStereoEvidenceDoesNotClaimStereo() {
        val report = loader().lineSequence()
            .filterNot { it.startsWith("primary_stereo_supported=") }
            .joinToString("\n")
        val result = ExternalOpenXrCompatibilityAssessment.analyze(service(), report)
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.STEREO_CONFIGURATION).status
        )
    }

    @Test
    fun cleanupFailureIsReportedIndependently() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(),
            loader().replace("xrDestroyInstance=XR_SUCCESS", "xrDestroyInstance=XR_ERROR_INSTANCE_LOST (-13)")
        )
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.Status.FAILED,
            result.stage(ExternalOpenXrCompatibilityAssessment.StageId.CLEANUP).status
        )
        assertTrue(result.warnings.any { it.contains("destruction") })
    }

    @Test
    fun summaryCountsAreDerivedFromStageStates() {
        val result = ExternalOpenXrCompatibilityAssessment.analyze(
            service(),
            loader(instance = "XR_ERROR_RUNTIME_UNAVAILABLE (-51)", created = "NO", includeSession = false)
        )
        assertEquals(result.stages.count { it.status == ExternalOpenXrCompatibilityAssessment.Status.VERIFIED }, result.verifiedCount)
        assertEquals(result.stages.count { it.status == ExternalOpenXrCompatibilityAssessment.Status.FAILED }, result.failedCount)
        assertEquals(result.stages.count { it.status == ExternalOpenXrCompatibilityAssessment.Status.BLOCKED }, result.blockedCount)
    }
}
