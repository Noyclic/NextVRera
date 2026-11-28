package com.noyclic.xrclientprobe

/**
 * Reports which raw fields were captured for each assessment stage.
 *
 * Coverage is about diagnostic-field presence only. It is not a compatibility
 * percentage, runtime quality score, or proof that a verified stage is correct.
 */
internal object ExternalOpenXrEvidenceCoverage {
    enum class Completeness { COMPLETE, PARTIAL, ABSENT, NOT_APPLICABLE }

    data class StageCoverage(
        val stageId: ExternalOpenXrCompatibilityAssessment.StageId,
        val stageStatus: ExternalOpenXrCompatibilityAssessment.Status,
        val completeness: Completeness,
        val expectedKeys: List<String>,
        val observedKeys: List<String>,
        val missingKeys: List<String>,
        val missingAlternativeGroups: List<List<String>>
    ) {
        val expectedCount: Int get() = expectedKeys.size
        val observedCount: Int get() = observedKeys.size

        fun toDisplayText(): String = buildString {
            appendLine(stageId.name + ": " + completeness.name + " (stage=" + stageStatus.name + ")")
            if (observedKeys.isNotEmpty()) appendLine("  observed=" + observedKeys.joinToString(","))
            if (missingKeys.isNotEmpty()) appendLine("  missing=" + missingKeys.joinToString(","))
            if (missingAlternativeGroups.isNotEmpty()) {
                appendLine("  alternatives=" + missingAlternativeGroups.joinToString(";") {
                    it.joinToString("|")
                })
            }
        }
    }

    data class Report(val stages: List<StageCoverage>) {
        val completeCount: Int get() = stages.count { it.completeness == Completeness.COMPLETE }
        val partialCount: Int get() = stages.count { it.completeness == Completeness.PARTIAL }
        val absentCount: Int get() = stages.count { it.completeness == Completeness.ABSENT }
        val notApplicableCount: Int get = stages.count { it.completeness == Completeness.NOT_APPLICABLE }
        val expectedFieldCount: Int get() = stages.sumOf { it.expectedCount }
        val observedFieldCount: Int get() = stages.sumOf { it.observedCount }
        val missingFieldCount: Int get() = stages.sumOf { it.missingKeys.size }

        fun stage(id: ExternalOpenXrCompatibilityAssessment.StageId): StageCoverage =
            stages.first { it.stageId == id }

        fun toDisplayText(): String = buildString {
            appendLine("OPENXR EVIDENCE FIELD COVERAGE")
            appendLine("complete_stages=" + completeCount)
            appendLine("partial_stages=" + partialCount)
            appendLine("absent_stages=" + absentCount)
            appendLine("not_applicable_stages=" + notApplicableCount)
            appendLine("expected_field_slots=" + expectedFieldCount)
            appendLine("observed_field_slots=" + observedFieldCount)
            appendLine("missing_field_slots=" + missingFieldCount)
            appendLine("This is field-presence coverage only, not a compatibility score.")
            stages.forEach { appendLine(it.toDisplayText()) }
        }

        fun toJson(): String = buildString {
            append('{')
            append("\"complete_stages\":").append(completeCount).append(',')
            append("\"partial_stages\":").append(partialCount).append(',')
            append("\"absent_stages\":").append(absentCount).append(',')
            append("\"not_applicable_stages\":").append(notApplicableCount).append(',')
            append("\"expected_field_slots\":").append(expectedFieldCount).append(',')
            append("\"observed_field_slots\":").append(observedFieldCount).append(',')
            append("\"missing_field_slots\":").append(missingFieldCount).append(',')
            append("\"stages\":[")
            stages.forEachIndexed { index, stage ->
                if (index > 0) append(',')
                append('{')
                append("\"stage\":").append(json(stage.stageId.name)).append(',')
                append("\"stage_status\":").append(json(stage.stageStatus.name)).append(',')
                append("\"completeness\":").append(json(stage.completeness.name)).append(',')
                append("\"expected_keys\":").append(stringArray(stage.expectedKeys)).append(',')
                append("\"observed_keys\":").append(stringArray(stage.observedKeys)).append(',')
                append("\"missing_keys\":").append(stringArray(stage.missingKeys)).append(',')
                append("\"missing_alternative_groups\":").append(
                    stage.missingAlternativeGroups.joinToString(prefix = "[", postfix = "]") {
                        stringArray(it)
                    }
                )
                append('}')
            }
            append("]}")
        }

        private fun stringArray(values: List<String>): String =
            values.joinToString(prefix = "[", postfix = "]") { json(it) }
    }

    private data class Contract(
        val id: ExternalOpenXrCompatibilityAssessment.StageId,
        val requiredGroups: List<List<String>>
    ) {
        val expectedKeys: List<String> get() = requiredGroups.flatten().distinct()
    }

    private val contracts = listOf(
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY, listOf(
            listOf("EndXR service visible"),
            listOf("EndXR service exported"),
            listOf("Runtime library exists")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER, listOf(
            listOf("Installable RuntimeBroker"),
            listOf("System RuntimeBroker")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION, listOf(
            listOf("xrInitializeLoaderKHR call")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.EXTENSION_ENUMERATION, listOf(
            listOf("xrEnumerateInstanceExtensionProperties(count)", "xrEnumerateInstanceExtensionProperties(list)")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION, listOf(
            listOf("xrCreateInstance"),
            listOf("runtime_instance_created")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.HMD_SYSTEM, listOf(
            listOf("xrGetSystem(HMD)")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.STEREO_CONFIGURATION, listOf(
            listOf("primary_stereo_supported"),
            listOf("xrEnumerateViewConfigurations(list)", "xrEnumerateViewConfigurations(count)")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING, listOf(
            listOf("external_egl_initialize"),
            listOf("external_egl_context"),
            listOf("xrGetOpenGLESGraphicsRequirementsKHR")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.SESSION_CREATION, listOf(
            listOf("external_xrCreateSession"),
            listOf("external_gles_session")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN, listOf(
            listOf("external_xrCreateSwapchain"),
            listOf("external_xrEnumerateSwapchainImages")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP, listOf(
            listOf("external_xrWaitFrame"),
            listOf("external_xrBeginFrame"),
            listOf("external_xrEndFrame(projection)", "external_xrEndFrame(empty_fallback)")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION, listOf(
            listOf("external_projection_layer_submission"),
            listOf("external_projection_swapchain_image_verification")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING, listOf(
            listOf("runtime_advertises_XR_EXT_hand_tracking"),
            listOf("external_xrCreateHandTrackerEXT_left"),
            listOf("external_xrCreateHandTrackerEXT_right"),
            listOf("external_xrLocateHandJointsEXT_left"),
            listOf("external_xrLocateHandJointsEXT_right")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE, listOf(
            listOf("EndXR input IPC")
        )),
        Contract(ExternalOpenXrCompatibilityAssessment.StageId.CLEANUP, listOf(
            listOf("xrDestroyInstance"),
            listOf("external_xrDestroySession")
        ))
    )

    fun analyze(
        serviceReport: String,
        loaderReport: String,
        stages: List<ExternalOpenXrCompatibilityAssessment.Stage>
    ): Report {
        val serviceKeys = parseKeys(serviceReport)
        val loaderKeys = parseKeys(loaderReport)
        val stageById = stages.associateBy { it.id }
        val rows = contracts.map { contract ->
            val stage = stageById[contract.id]
            val status = stage?.status ?: ExternalOpenXrCompatibilityAssessment.Status.UNKNOWN
            val available = if (
                contract.id == ExternalOpenXrCompatibilityAssessment.StageId.SERVICE_DISCOVERY ||
                contract.id == ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER ||
                contract.id == ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE
            ) serviceKeys else loaderKeys
            val observed = contract.expectedKeys.filter { it in available }
            val missingGroups = contract.requiredGroups.filter { group -> group.none { it in available } }
            val missing = missingGroups.flatten().distinct()
            val completeness = when {
                status == ExternalOpenXrCompatibilityAssessment.Status.NOT_RUN -> Completeness.NOT_APPLICABLE
                observed.isEmpty() -> Completeness.ABSENT
                missingGroups.isEmpty() -> Completeness.COMPLETE
                else -> Completeness.PARTIAL
            }
            StageCoverage(
                stageId = contract.id,
                stageStatus = status,
                completeness = completeness,
                expectedKeys = contract.expectedKeys,
                observedKeys = observed,
                missingKeys = missing,
                missingAlternativeGroups = missingGroups.filter { it.size > 1 }
            )
        }
        return Report(rows)
    }

    private fun parseKeys(report: String): Set<String> {
        val keys = linkedSetOf<String>()
        report.take(MAX_REPORT_LENGTH).lineSequence().forEach { raw ->
            val line = raw.trim()
            val equals = line.indexOf('=')
            val colon = line.indexOf(':')
            val delimiter = when {
                equals > 0 && (colon < 0 || equals < colon) -> equals
                colon > 0 -> colon
                else -> -1
            }
            if (delimiter <= 0) return@forEach
            val key = line.substring(0, delimiter).trim()
            if (KEY_PATTERN.matches(key)) keys += key
        }
        return keys
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

    private val KEY_PATTERN = Regex("[A-Za-z0-9_(). -]+")
    private const val MAX_REPORT_LENGTH = 256 * 1024
}
