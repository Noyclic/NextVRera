package com.noyclic.xrclientprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenXrAssessmentHistoryTest {
    private fun snapshot(
        timestamp: Long = 1L,
        runtime: String? = "com.noyclic.questphonexr",
        statuses: Map<String, String> = mapOf(
            "SERVICE_DISCOVERY" to "VERIFIED",
            "RUNTIME_BROKER" to "VERIFIED",
            "LOADER_INITIALIZATION" to "VERIFIED",
            "INSTANCE_CREATION" to "VERIFIED",
            "SESSION_CREATION" to "NOT_RUN",
            "PROJECTION_SUBMISSION" to "NOT_RUN"
        ),
        failure: String? = null
    ) = ExternalOpenXrAssessmentHistory.Snapshot(
        timestampMillis = timestamp,
        selectedRuntimePackage = runtime,
        highestVerifiedStage = "INSTANCE_CREATION",
        firstFailureKey = failure,
        stageStatuses = statuses
    )

    @Test
    fun verifiedToFailedIsRegression() {
        val previous = snapshot()
        val current = snapshot(
            timestamp = 2L,
            statuses = previous.stageStatuses + ("INSTANCE_CREATION" to "FAILED"),
            failure = "xrCreateInstance"
        )
        val comparison = ExternalOpenXrAssessmentHistory.compare(previous, current)
        assertEquals(1, comparison.regressions.size)
        assertEquals("INSTANCE_CREATION", comparison.regressions.single().stageId)
        assertEquals(
            ExternalOpenXrAssessmentHistory.ChangeKind.REGRESSED,
            comparison.regressions.single().kind
        )
    }

    @Test
    fun failedToVerifiedIsImprovement() {
        val previous = snapshot(
            statuses = snapshot().stageStatuses + ("SESSION_CREATION" to "FAILED"),
            failure = "external_xrCreateSession"
        )
        val current = snapshot(
            timestamp = 2L,
            statuses = previous.stageStatuses + ("SESSION_CREATION" to "VERIFIED"),
            failure = null
        )
        val comparison = ExternalOpenXrAssessmentHistory.compare(previous, current)
        assertEquals(1, comparison.improvements.size)
        assertEquals("SESSION_CREATION", comparison.improvements.single().stageId)
    }

    @Test
    fun unknownToNotRunIsChangeNotRegression() {
        val previous = snapshot(
            statuses = snapshot().stageStatuses + ("SWAPCHAIN" to "UNKNOWN")
        )
        val current = snapshot(
            timestamp = 2L,
            statuses = previous.stageStatuses + ("SWAPCHAIN" to "NOT_RUN")
        )
        val comparison = ExternalOpenXrAssessmentHistory.compare(previous, current)
        assertTrue(comparison.regressions.isEmpty())
        assertTrue(comparison.improvements.isEmpty())
        assertEquals(1, comparison.otherChanges.size)
    }

    @Test
    fun unverifiedToVerifiedIsImprovement() {
        val previous = snapshot(
            statuses = snapshot().stageStatuses + ("PROJECTION_SUBMISSION" to "PARTIAL")
        )
        val current = snapshot(
            timestamp = 2L,
            statuses = previous.stageStatuses + ("PROJECTION_SUBMISSION" to "VERIFIED")
        )
        val comparison = ExternalOpenXrAssessmentHistory.compare(previous, current)
        assertEquals("PROJECTION_SUBMISSION", comparison.improvements.single().stageId)
    }

    @Test
    fun identicalSnapshotsHaveNoChanges() {
        val previous = snapshot()
        val current = previous.copy(timestampMillis = 2L)
        val comparison = ExternalOpenXrAssessmentHistory.compare(previous, current)
        assertTrue(comparison.changes.all {
            it.kind == ExternalOpenXrAssessmentHistory.ChangeKind.UNCHANGED
        })
        assertEquals(6, comparison.unchangedCount)
        assertTrue(comparison.regressions.isEmpty())
    }

    @Test
    fun changedRuntimePackageIsVisibleInComparison() {
        val previous = snapshot(runtime = "org.example.runtime")
        val current = snapshot(timestamp = 2L, runtime = "com.noyclic.questphonexr")
        val text = ExternalOpenXrAssessmentHistory.compare(previous, current).toDisplayText()
        assertTrue(text.contains("previous_runtime=org.example.runtime"))
        assertTrue(text.contains("current_runtime=com.noyclic.questphonexr"))
    }

    @Test
    fun firstFailureChangeIsReported() {
        val previous = snapshot(failure = "xrCreateInstance")
        val current = snapshot(timestamp = 2L, failure = "external_xrCreateSession")
        val text = ExternalOpenXrAssessmentHistory.compare(previous, current).toDisplayText()
        assertTrue(text.contains("first_failure_changed=xrCreateInstance -> external_xrCreateSession"))
    }

    @Test
    fun missingStageInOneSnapshotIsIgnoredRatherThanInvented() {
        val previous = snapshot()
        val current = snapshot(
            timestamp = 2L,
            statuses = previous.stageStatuses - "SESSION_CREATION"
        )
        val comparison = ExternalOpenXrAssessmentHistory.compare(previous, current)
        assertFalse(comparison.changes.any { it.stageId == "SESSION_CREATION" })
    }

    @Test
    fun stageOrderingFollowsOpenXrPipelineDefinition() {
        val previous = snapshot()
        val current = snapshot(timestamp = 2L)
        val ids = ExternalOpenXrAssessmentHistory.compare(previous, current).changes.map { it.stageId }
        assertEquals(
            listOf(
                "SERVICE_DISCOVERY",
                "RUNTIME_BROKER",
                "LOADER_INITIALIZATION",
                "INSTANCE_CREATION",
                "SESSION_CREATION",
                "PROJECTION_SUBMISSION"
            ),
            ids
        )
    }

    @Test
    fun comparisonExplainsRegressionWithoutClaimingPerformance() {
        val previous = snapshot()
        val current = snapshot(
            timestamp = 2L,
            statuses = previous.stageStatuses + ("RUNTIME_BROKER" to "BLOCKED")
        )
        val text = ExternalOpenXrAssessmentHistory.compare(previous, current).toDisplayText()
        assertTrue(text.contains("VERIFIED_STAGE_REGRESSION_DETECTED"))
        assertTrue(text.contains("not a performance benchmark"))
    }
}
