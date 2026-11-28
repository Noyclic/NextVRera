package com.noyclic.xrclientprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenXrRecoveryAdvisorTest {
    private fun stage(
        id: ExternalOpenXrCompatibilityAssessment.StageId,
        status: ExternalOpenXrCompatibilityAssessment.Status,
        next: String? = "Inspect the exact evidence and prerequisite."
    ) = ExternalOpenXrCompatibilityAssessment.Stage(
        id = id,
        title = id.name.replace('_', ' '),
        status = status,
        evidence = "fixture evidence for " + id.name,
        nextStep = next
    )

    private fun integrity(
        code: String = "INSTANCE_CREATED_AFTER_CREATE_FAILURE",
        severity: ExternalOpenXrReportIntegrity.Severity = ExternalOpenXrReportIntegrity.Severity.BLOCKING
    ) = ExternalOpenXrReportIntegrity.Report(
        listOf(
            ExternalOpenXrReportIntegrity.Finding(
                code = code,
                severity = severity,
                message = "Contradictory fixture evidence.",
                evidenceKeys = listOf("xrCreateInstance", "runtime_instance_created"),
                recommendation = "Reconcile the raw fields."
            )
        )
    )

    @Test
    fun reportConflictTakesPriorityOverARecordedRuntimeFailure() {
        val failure = ExternalOpenXrCompatibilityAssessment.Failure(
            key = "xrCreateInstance",
            result = "XR_ERROR_RUNTIME_UNAVAILABLE (-51)",
            explanation = "No active runtime.",
            stage = ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION
        )
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(failure.result, failure.key, failure.stage)
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(failure.stage!!, ExternalOpenXrCompatibilityAssessment.Status.BLOCKED)),
            firstFailure = failure,
            diagnosis = diagnosis,
            integrity = integrity()
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.REPORT_CONFLICT, plan.state)
        assertEquals(ExternalOpenXrRecoveryAdvisor.RetryPolicy.HUMAN_REVIEW, plan.retryPolicy)
        assertTrue(plan.primaryCause.contains("INSTANCE_CREATED_AFTER_CREATE_FAILURE"))
    }

    @Test
    fun runtimeUnavailablePlanStartsWithRuntimeBrokerNotGraphics() {
        val stageId = ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_RUNTIME_UNAVAILABLE (-51)", "xrCreateInstance", stageId
        )
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(stageId, ExternalOpenXrCompatibilityAssessment.Status.BLOCKED)),
            firstFailure = ExternalOpenXrCompatibilityAssessment.Failure(
                "xrCreateInstance", "XR_ERROR_RUNTIME_UNAVAILABLE (-51)",
                "No runtime selected.", stageId
            ),
            diagnosis = diagnosis,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.RETRY_AFTER_CHANGE, plan.state)
        assertTrue(plan.actions.single().steps.any { it.contains("RuntimeBroker") })
        assertFalse(plan.actions.single().steps.any { it.contains("swapchain", true) })
    }

    @Test
    fun lostSessionPlanForbidsReusingOldHandles() {
        val stageId = ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_SESSION_LOST", "external_xrWaitFrame", stageId
        )
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(stageId, ExternalOpenXrCompatibilityAssessment.Status.FAILED)),
            firstFailure = ExternalOpenXrCompatibilityAssessment.Failure(
                "external_xrWaitFrame", "XR_ERROR_SESSION_LOST", "Session lost.", stageId
            ),
            diagnosis = diagnosis,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.RetryPolicy.DO_NOT_RETRY_SAME_HANDLES, plan.retryPolicy)
        assertTrue(plan.stopCondition.contains("lost session or instance"))
    }

    @Test
    fun instanceLostPlanDoesNotReuseOldHandles() {
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_INSTANCE_LOST", "xrDestroyInstance"
        )
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(ExternalOpenXrCompatibilityAssessment.StageId.CLEANUP, ExternalOpenXrCompatibilityAssessment.Status.FAILED)),
            firstFailure = null,
            diagnosis = diagnosis,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.RetryPolicy.DO_NOT_RETRY_SAME_HANDLES, plan.retryPolicy)
    }

    @Test
    fun firstFailedStageIsChosenWhenThereIsNoSymbolicResultDiagnosis() {
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(
                stage(ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING, ExternalOpenXrCompatibilityAssessment.Status.FAILED),
                stage(ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN, ExternalOpenXrCompatibilityAssessment.Status.FAILED)
            ),
            firstFailure = null,
            diagnosis = null,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.ACTION_REQUIRED, plan.state)
        assertTrue(plan.primaryCause.contains("GRAPHICS_BINDING"))
        assertEquals(1, plan.actions.first().priority)
    }

    @Test
    fun blockedOptionalCapabilityDoesNotBecomeCoreRuntimeFailure() {
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(
                ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING,
                ExternalOpenXrCompatibilityAssessment.Status.BLOCKED,
                "The runtime did not advertise hand tracking."
            )),
            firstFailure = null,
            diagnosis = null,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.ACTION_REQUIRED, plan.state)
        assertTrue(plan.stopCondition.contains("optional capabilities"))
    }

    @Test
    fun partialOrUnknownStageProducesEvidenceRecapturePlan() {
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(
                stage(ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY, ExternalOpenXrCompatibilityAssessment.Status.VERIFIED),
                stage(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER, ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN)
            ),
            firstFailure = null,
            diagnosis = null,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.EVIDENCE_INCOMPLETE, plan.state)
        assertEquals(ExternalOpenXrRecoveryAdvisor.RetryPolicy.RECAPTURE_REPORT, plan.retryPolicy)
    }

    @Test
    fun allVerifiedProbeStagesStillRequireSeparateClientValidation() {
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = ExternalOpenXrCompatibilityAssessment.StageId.values().map {
                stage(it, ExternalOpenXrCompatibilityAssessment.Status.VERIFIED)
            },
            firstFailure = null,
            diagnosis = null,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.PROBE_STAGES_VERIFIED, plan.state)
        assertTrue(plan.actions.single().steps.any { it.contains("separately packaged") })
        assertTrue(plan.scopeWarning.contains("arbitrary Quest APK compatibility"))
    }

    @Test
    fun unknownVendorResultNeedsHumanReviewInsteadOfInventedFix() {
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_FUTURE_VENDOR_CODE (-90001)", "xrCreateInstance"
        )
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION, ExternalOpenXrCompatibilityAssessment.Status.FAILED)),
            firstFailure = null,
            diagnosis = diagnosis,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.RetryPolicy.HUMAN_REVIEW, plan.retryPolicy)
        assertTrue(plan.stopCondition.contains("unknown or vendor-specific"))
    }

    @Test
    fun boundedRetryIsOnlyRecommendedAfterRuntimeRecovery() {
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_RUNTIME_FAILURE", "xrInitializeLoaderKHR call"
        )
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION, ExternalOpenXrCompatibilityAssessment.Status.FAILED)),
            firstFailure = null,
            diagnosis = diagnosis,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.RetryPolicy.ONE_BOUNDED_RETRY_AFTER_RECOVERY, plan.retryPolicy)
        assertTrue(plan.retryCondition.contains("bounded retry"))
    }

    @Test
    fun planTextAndJsonAreSelfContained() {
        val diagnosis = ExternalOpenXrFailureCatalog.diagnose(
            "XR_ERROR_EXTENSION_NOT_PRESENT", "external_xrCreateHandTrackerEXT_left"
        )
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = listOf(stage(ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING, ExternalOpenXrCompatibilityAssessment.Status.FAILED)),
            firstFailure = null,
            diagnosis = diagnosis,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        val text = plan.toDisplayText()
        val json = plan.toJson()
        assertTrue(text.contains("OPENXR RECOVERY PLAN"))
        assertTrue(text.contains("retry_policy="))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"actions\""))
    }

    @Test
    fun actionCountAndStepCountAreBounded() {
        val findings = (1..10).map {
            ExternalOpenXrReportIntegrity.Finding(
                "CHECK_" + it,
                ExternalOpenXrReportIntegrity.Severity.HIGH,
                "Finding " + it,
                listOf("field_" + it),
                "Reconcile finding " + it
            )
        }
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = emptyList(),
            firstFailure = null,
            diagnosis = null,
            integrity = ExternalOpenXrReportIntegrity.Report(findings)
        )
        assertTrue(plan.actions.size <= 4)
        assertTrue(plan.actions.all { it.steps.size <= 8 })
    }

    @Test
    fun noFailureRecordedWithEmptyStagesRequestsEvidence() {
        val plan = ExternalOpenXrRecoveryAdvisor.build(
            stages = emptyList(),
            firstFailure = null,
            diagnosis = null,
            integrity = ExternalOpenXrReportIntegrity.Report(emptyList())
        )
        assertEquals(ExternalOpenXrRecoveryAdvisor.PlanState.EVIDENCE_INCOMPLETE, plan.state)
        assertNotNull(plan.scopeWarning)
    }
}
