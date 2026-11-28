package com.noyclic.xrclientprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenXrEvidenceCoverageTest {
    private fun service() = """
        EndXR service visible: YES
        EndXR service exported: YES
        Runtime library exists: YES
        Installable RuntimeBroker: provider found; no active runtime selected
        System RuntimeBroker: provider not installed or not visible
        EndXR input IPC: provider_permission=GRANTED; provider_query=PASS
    """.trimIndent()

    private fun loader() = """
        xrInitializeLoaderKHR call=XR_SUCCESS
        xrEnumerateInstanceExtensionProperties(count)=XR_SUCCESS
        xrCreateInstance=XR_SUCCESS
        runtime_instance_created=YES
        xrGetSystem(HMD)=XR_SUCCESS
        primary_stereo_supported=YES
        xrEnumerateViewConfigurations(list)=XR_SUCCESS
        external_egl_initialize=PASS
        external_egl_context=PASS
        xrGetOpenGLESGraphicsRequirementsKHR=XR_SUCCESS
        external_xrCreateSession=XR_SUCCESS
        external_gles_session=PASS
        external_xrCreateSwapchain=XR_SUCCESS
        external_xrEnumerateSwapchainImages=XR_SUCCESS
        external_xrWaitFrame=XR_SUCCESS
        external_xrBeginFrame=XR_SUCCESS
        external_xrEndFrame(projection)=XR_SUCCESS
        external_projection_layer_submission=PASS
        external_projection_swapchain_image_verification=PASS
        runtime_advertises_XR_EXT_hand_tracking=YES
        external_xrCreateHandTrackerEXT_left=XR_SUCCESS
        external_xrCreateHandTrackerEXT_right=XR_SUCCESS
        external_xrLocateHandJointsEXT_left=XR_SUCCESS
        external_xrLocateHandJointsEXT_right=XR_SUCCESS
        external_xrDestroySession=XR_SUCCESS
        xrDestroyInstance=XR_SUCCESS
    """.trimIndent()

    private fun stages(
        status: ExternalOpenXrCompatibilityAssessment.Status =
            ExternalOpenXrCompatibilityAssessment.Status.VERIFIED
    ) = ExternalOpenXrCompatibilityAssessment.StageId.values().map {
        ExternalOpenXrCompatibilityAssessment.Stage(
            id = it,
            title = it.name,
            status = status,
            evidence = "fixture"
        )
    }

    @Test
    fun completeFixtureCoversAllStageContracts() {
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), loader(), stages())
        assertEquals(15, report.completeCount)
        assertEquals(0, report.partialCount)
        assertEquals(0, report.absentCount)
        assertEquals(0, report.notApplicableCount)
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING).completeness
        )
    }

    @Test
    fun reportContainsOneCoverageRowForEveryAssessmentStage() {
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), loader(), stages())
        assertEquals(
            ExternalOpenXrCompatibilityAssessment.StageId.values().toSet(),
            report.stages.map { it.stageId }.toSet()
        )
    }

    @Test
    fun missingFieldsProducePartialCoverageWithoutChangingStageStatus() {
        val actualStages = stages().map {
            if (it.id == ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING) {
                it.copy(status = ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN)
            } else it
        }
        val report = ExternalOpenXrEvidenceCoverage.analyze(
            service(),
            loader().lineSequence().filterNot { it.startsWith("external_egl_context=") }.joinToString("\n"),
            actualStages
        )
        val graphics = report.stage(ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING)
        assertEquals(ExternalOpenXrEvidenceCoverage.Completeness.PARTIAL, graphics.completeness)
        assertEquals(ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN, graphics.stageStatus)
        assertTrue(graphics.missingKeys.contains("external_egl_context"))
    }

    @Test
    fun stageWithNoFieldsIsAbsent() {
        val report = ExternalOpenXrEvidenceCoverage.analyze("", "", stages())
        val loader = report.stage(ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION)
        assertEquals(ExternalOpenXrEvidenceCoverage.Completeness.ABSENT, loader.completeness)
        assertEquals(listOf("xrInitializeLoaderKHR call"), loader.missingKeys)
    }

    @Test
    fun skippedStageIsNotApplicableRatherThanAbsent() {
        val stageRows = stages().map {
            if (it.id == ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING) {
                it.copy(status = ExternalOpenXrCompatibilityAssessment.Status.NOT_RUN)
            } else it
        }
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), loader(), stageRows)
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.NOT_APPLICABLE,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING).completeness
        )
    }

    @Test
    fun alternativeEnumerationResultCanSatisfyTheContract() {
        val raw = loader().lineSequence()
            .filterNot { it.startsWith("xrEnumerateInstanceExtensionProperties(count)=") }
            .joinToString("\n")
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), raw, stages())
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.EXTENSION_ENUMERATION).completeness
        )
    }

    @Test
    fun missingBothEnumerationAlternativesIsAbsentWhenNoStageEvidenceExists() {
        val raw = loader().lineSequence()
            .filterNot { it.startsWith("xrEnumerateInstanceExtensionProperties(") }
            .joinToString("\n")
        val stageRows = stages().map {
            if (it.id == ExternalOpenXrCompatibilityAssessment.StageId.EXTENSION_ENUMERATION) {
                it.copy(status = ExternalOpenXrCompatibilityAssessment.Status.PARTIAL)
            } else it
        }
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), raw, stageRows)
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.ABSENT,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.EXTENSION_ENUMERATION).completeness
        )
    }

    @Test
    fun completeFieldCoverageDoesNotPromoteUnknownStageToVerified() {
        val stageRows = stages().map {
            if (it.id == ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN) {
                it.copy(status = ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN)
            } else it
        }
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), loader(), stageRows)
        val swapchain = report.stage(ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN)
        assertEquals(ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE, swapchain.completeness)
        assertEquals(ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN, swapchain.stageStatus)
    }

    @Test
    fun serviceLabelKeysAreReadFromColonDelimitedFields() {
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), loader(), stages())
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY).completeness
        )
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE).completeness
        )
    }

    @Test
    fun brokerCoverageRequiresBothProviderOutcomes() {
        val raw = service().lineSequence()
            .filterNot { it.startsWith("System RuntimeBroker:") }
            .joinToString("\n")
        val report = ExternalOpenXrEvidenceCoverage.analyze(raw, loader(), stages())
        val broker = report.stage(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER)
        assertEquals(ExternalOpenXrEvidenceCoverage.Completeness.PARTIAL, broker.completeness)
        assertTrue(broker.missingKeys.contains("System RuntimeBroker"))
    }

    @Test
    fun alternativeFrameEndKeysAreAccepted() {
        val raw = loader().replace(
            "external_xrEndFrame(projection)=XR_SUCCESS",
            "external_xrEndFrame(empty_fallback)=XR_SUCCESS"
        )
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), raw, stages())
        assertEquals(
            ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE,
            report.stage(ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP).completeness
        )
    }

    @Test
    fun evidenceCountsAreDerivedFromRows() {
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), "", stages())
        assertEquals(report.stages.count { it.completeness == ExternalOpenXrEvidenceCoverage.Completeness.COMPLETE }, report.completeCount)
        assertEquals(report.stages.count { it.completeness == ExternalOpenXrEvidenceCoverage.Completeness.PARTIAL }, report.partialCount)
        assertEquals(report.stages.count { it.completeness == ExternalOpenXrEvidenceCoverage.Completeness.ABSENT }, report.absentCount)
        assertEquals(report.stages.count { it.completeness == ExternalOpenXrEvidenceCoverage.Completeness.NOT_APPLICABLE }, report.notApplicableCount)
    }

    @Test
    fun evidenceCoverageJsonAndTextExplainThatItIsNotCompatibilityScore() {
        val report = ExternalOpenXrEvidenceCoverage.analyze(service(), loader(), stages())
        assertTrue(report.toDisplayText().contains("not a compatibility score"))
        assertTrue(report.toJson().contains("\"missing_keys\""))
        assertTrue(report.toJson().startsWith("{"))
        assertTrue(report.toJson().endsWith("}"))
    }

    @Test
    fun boundedParserDoesNotThrowOnUnstructuredLongInput() {
        val huge = "unstructured\n".repeat(50_000)
        val report = ExternalOpenXrEvidenceCoverage.analyze(huge, huge, emptyList())
        assertEquals(15, report.stages.size)
        assertTrue(report.stages.all {
            it.completeness == ExternalOpenXrEvidenceCoverage.Completeness.ABSENT ||
                it.completeness == ExternalOpenXrEvidenceCoverage.Completeness.NOT_APPLICABLE
        })
        assertFalse(report.toJson().contains("unstructured"))
    }
}
