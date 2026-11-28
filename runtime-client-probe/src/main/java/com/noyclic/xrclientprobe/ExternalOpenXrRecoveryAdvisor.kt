package com.noyclic.xrclientprobe

/**
 * Converts an evidence-based assessment into a safe, ordered troubleshooting plan.
 *
 * The advisor is intentionally read-only. It never retries OpenXR calls, changes
 * RuntimeBroker selection, changes permissions, or launches an application. Its
 * output is a human-readable order of operations so the first failed prerequisite
 * is fixed before later, dependent subsystems are investigated.
 */
internal object ExternalOpenXrRecoveryAdvisor {
    enum class PlanState {
        REPORT_CONFLICT,
        ACTION_REQUIRED,
        RETRY_AFTER_CHANGE,
        PROBE_STAGES_VERIFIED,
        EVIDENCE_INCOMPLETE
    }

    enum class RetryPolicy {
        DO_NOT_RETRY_SAME_HANDLES,
        RETRY_AFTER_PREREQUISITE_CHANGE,
        ONE_BOUNDED_RETRY_AFTER_RECOVERY,
        RECAPTURE_REPORT,
        NO_RETRY_REQUIRED,
        HUMAN_REVIEW
    }

    data class Action(
        val priority: Int,
        val title: String,
        val reason: String,
        val steps: List<String>,
        val expectedEvidence: String
    )

    data class Plan(
        val state: PlanState,
        val primaryCause: String,
        val retryPolicy: RetryPolicy,
        val retryCondition: String,
        val stopCondition: String,
        val actions: List<Action>,
        val scopeWarning: String
    ) {
        fun toDisplayText(): String = buildString {
            appendLine("OPENXR RECOVERY PLAN")
            appendLine("state=" + state.name)
            appendLine("primary_cause=" + primaryCause)
            appendLine("retry_policy=" + retryPolicy.name)
            appendLine("retry_condition=" + retryCondition)
            appendLine("stop_condition=" + stopCondition)
            actions.forEach { action ->
                appendLine()
                appendLine("ACTION " + action.priority + ": " + action.title)
                appendLine("why=" + action.reason)
                action.steps.forEachIndexed { index, step ->
                    appendLine((index + 1).toString() + ". " + step)
                }
                appendLine("expected_evidence=" + action.expectedEvidence)
            }
            appendLine()
            append("scope=" + scopeWarning)
        }

        fun toJson(): String = buildString {
            append('{')
            append("\"state\":").append(json(state.name)).append(',')
            append("\"primary_cause\":").append(json(primaryCause)).append(',')
            append("\"retry_policy\":").append(json(retryPolicy.name)).append(',')
            append("\"retry_condition\":").append(json(retryCondition)).append(',')
            append("\"stop_condition\":").append(json(stopCondition)).append(',')
            append("\"actions\":[")
            actions.forEachIndexed { index, action ->
                if (index > 0) append(',')
                append('{')
                append("\"priority\":").append(action.priority).append(',')
                append("\"title\":").append(json(action.title)).append(',')
                append("\"reason\":").append(json(action.reason)).append(',')
                append("\"steps\":").append(jsonArray(action.steps)).append(',')
                append("\"expected_evidence\":").append(json(action.expectedEvidence))
                append('}')
            }
            append("],\"scope_warning\":").append(json(scopeWarning))
            append('}')
        }
    }

    fun build(
        stages: List<ExternalOpenXrCompatibilityAssessment.Stage>,
        firstFailure: ExternalOpenXrCompatibilityAssessment.Failure?,
        diagnosis: ExternalOpenXrFailureCatalog.Diagnosis?,
        integrity: ExternalOpenXrReportIntegrity.Report
    ): Plan {
        val criticalFinding = integrity.findings.firstOrNull {
            it.severity == ExternalOpenXrReportIntegrity.Severity.BLOCKING ||
                it.severity == ExternalOpenXrReportIntegrity.Severity.HIGH
        }
        if (criticalFinding != null) {
            return Plan(
                state = PlanState.REPORT_CONFLICT,
                primaryCause = "The diagnostic report contains conflicting evidence: " + criticalFinding.code,
                retryPolicy = RetryPolicy.HUMAN_REVIEW,
                retryCondition = "Do not draw a new compatibility conclusion until the raw fields are reconciled.",
                stopCondition = "Stop downstream diagnosis when success and failure fields cannot be tied to the same probe attempt.",
                actions = integrity.findings
                    .filter {
                        it.severity == ExternalOpenXrReportIntegrity.Severity.BLOCKING ||
                            it.severity == ExternalOpenXrReportIntegrity.Severity.HIGH
                    }
                    .take(MAX_ACTIONS)
                    .mapIndexed { index, finding ->
                        Action(
                            priority = index + 1,
                            title = "Reconcile " + finding.code,
                            reason = finding.message,
                            steps = listOf(
                                "Compare the raw lines named by " + finding.evidenceKeys.joinToString(", "),
                                finding.recommendation,
                                "Rerun the separate client probe once with a fresh process and save its complete output."
                            ),
                            expectedEvidence = "A single, internally consistent sequence of observations from one probe run."
                        )
                    },
                scopeWarning = PROBE_SCOPE_WARNING
            )
        }

        if (diagnosis != null) {
            return planForDiagnosis(diagnosis)
        }

        val failed = stages.firstOrNull {
            it.status == ExternalOpenXrCompatibilityAssessment.Status.FAILED
        }
        if (failed != null) {
            val next = failed.nextStep ?: "Inspect the exact raw result for " + failed.id.name + "."
            return Plan(
                state = PlanState.ACTION_REQUIRED,
                primaryCause = failed.id.name + " failed: " + failed.evidence,
                retryPolicy = RetryPolicy.RETRY_AFTER_PREREQUISITE_CHANGE,
                retryCondition = "Retry only after the stage-specific prerequisite or implementation is changed.",
                stopCondition = "Do not investigate later stages as independent root causes while this prerequisite remains failed.",
                actions = listOf(
                    Action(
                        1,
                        "Resolve " + failed.title,
                        failed.evidence,
                        listOf(next, "Run the probe again and compare the first failed stage."),
                        "The failing stage returns XR_SUCCESS or is explicitly reported as optional and not required."
                    )
                ),
                scopeWarning = PROBE_SCOPE_WARNING
            )
        }

        val blocked = stages.firstOrNull {
            it.status == ExternalOpenXrCompatibilityAssessment.Status.BLOCKED
        }
        if (blocked != null) {
            return Plan(
                state = PlanState.ACTION_REQUIRED,
                primaryCause = blocked.id.name + " is blocked: " + blocked.evidence,
                retryPolicy = RetryPolicy.RETRY_AFTER_PREREQUISITE_CHANGE,
                retryCondition = "Retry after the blocker is removed or the required capability becomes available.",
                stopCondition = "Do not mark blocked optional capabilities as failed core rendering.",
                actions = listOf(
                    Action(
                        1,
                        "Resolve the blocker for " + blocked.title,
                        blocked.evidence,
                        listOf(blocked.nextStep ?: "Inspect the prerequisite and the active runtime capability list."),
                        "The capability is available, or the feature is explicitly treated as optional."
                    )
                ),
                scopeWarning = PROBE_SCOPE_WARNING
            )
        }

        val unknown = stages.filter {
            it.status == ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN ||
                it.status == ExternalOpenXrCompatibilityAssessment.Status.PARTIAL
        }
        if (unknown.isNotEmpty()) {
            val first = unknown.first()
            return Plan(
                state = PlanState.EVIDENCE_INCOMPLETE,
                primaryCause = "The earliest incomplete evidence is " + first.id.name + ".",
                retryPolicy = RetryPolicy.RECAPTURE_REPORT,
                retryCondition = "Repeat the probe after confirming the diagnostic APK and paired runtime APK are from the intended build.",
                stopCondition = "Do not treat absent or partial evidence as proof of success.",
                actions = listOf(
                    Action(
                        1,
                        "Capture missing evidence for " + first.title,
                        first.evidence,
                        listOf(
                            first.nextStep ?: "Inspect the raw service and loader reports for this stage.",
                            "Keep the separate service report and official loader report together.",
                            "Copy the complete report immediately after the probe."
                        ),
                        "A concrete status and supporting result for " + first.id.name + "."
                    )
                ),
                scopeWarning = PROBE_SCOPE_WARNING
            )
        }

        return Plan(
            state = PlanState.PROBE_STAGES_VERIFIED,
            primaryCause = "No failed or incomplete probe stage was recorded.",
            retryPolicy = RetryPolicy.NO_RETRY_REQUIRED,
            retryCondition = "No repeat is required for the same unchanged probe. Continue with a separate physical-device or target-client test.",
            stopCondition = "Do not equate this probe pass with arbitrary Quest APK compatibility or compositor presentation.",
            actions = listOf(
                Action(
                    1,
                    "Validate the next boundary",
                    "This probe only demonstrates the stages that were exercised by its own client process.",
                    listOf(
                        "Verify RuntimeBroker selection on the target device.",
                        "Test one separately packaged OpenXR application with its own logs.",
                        "Record physical-device and sustained-frame results independently."
                    ),
                    "A separate client succeeds on the target device and its render/input behavior is observed."
                )
            ),
            scopeWarning = PROBE_SCOPE_WARNING
        )
    }

    private fun planForDiagnosis(
        diagnosis: ExternalOpenXrFailureCatalog.Diagnosis
    ): Plan {
        val policy = when (diagnosis.code) {
            "XR_ERROR_RUNTIME_UNAVAILABLE",
            "XR_ERROR_API_VERSION_UNSUPPORTED",
            "XR_ERROR_EXTENSION_NOT_PRESENT",
            "XR_ERROR_FUNCTION_UNSUPPORTED",
            "XR_ERROR_GRAPHICS_DEVICE_INVALID",
            "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING",
            "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED",
            "XR_ERROR_SWAPCHAIN_RECT_INVALID",
            "XR_ERROR_CALL_ORDER_INVALID",
            "XR_ERROR_VALIDATION_FAILURE",
            "XR_ERROR_HANDLE_INVALID" -> RetryPolicy.RETRY_AFTER_PREREQUISITE_CHANGE
            "XR_ERROR_SESSION_LOST",
            "XR_ERROR_INSTANCE_LOST" -> RetryPolicy.DO_NOT_RETRY_SAME_HANDLES
            "XR_ERROR_FORM_FACTOR_UNAVAILABLE",
            "XR_ERROR_OUT_OF_MEMORY",
            "XR_ERROR_RUNTIME_FAILURE" -> RetryPolicy.ONE_BOUNDED_RETRY_AFTER_RECOVERY
            else -> RetryPolicy.HUMAN_REVIEW
        }
        val state = when (policy) {
            RetryPolicy.ONE_BOUNDED_RETRY_AFTER_RECOVERY,
            RetryPolicy.RETRY_AFTER_PREREQUISITE_CHANGE -> PlanState.RETRY_AFTER_CHANGE
            else -> PlanState.ACTION_REQUIRED
        }
        val action = Action(
            priority = 1,
            title = diagnosis.summary,
            reason = diagnosis.likelyCause,
            steps = (diagnosis.checks + diagnosis.actions).distinct().take(MAX_STEPS),
            expectedEvidence = "The same symbolic result no longer appears at the first failing call, and later stages are tested from a fresh lifecycle."
        )
        return Plan(
            state = state,
            primaryCause = diagnosis.code ?: diagnosis.summary,
            retryPolicy = policy,
            retryCondition = diagnosis.retryAdvice,
            stopCondition = when (policy) {
                RetryPolicy.DO_NOT_RETRY_SAME_HANDLES ->
                    "Stop using handles from the lost session or instance; create a fresh lifecycle only after recovery."
                RetryPolicy.HUMAN_REVIEW ->
                    "Keep the original result text and do not infer a fix for an unknown or vendor-specific result."
                else ->
                    "Do not retry the same failing call repeatedly without changing its prerequisite."
            },
            actions = listOf(action),
            scopeWarning = PROBE_SCOPE_WARNING
        )
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u" + char.code.toString(16).padStart(4, '0'))
                else append(char)
            }
        }
        append('"')
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { json(it) }

    private const val MAX_ACTIONS = 4
    private const val MAX_STEPS = 8
    private const val PROBE_SCOPE_WARNING =
        "Probe scope only: this plan does not certify OpenXR conformance, arbitrary Quest APK compatibility, Meta service availability, compositor output, true 6DoF tracking, or sustained frame performance."
}
