package com.noyclic.xrclientprobe

/**
 * Converts the raw two-process diagnostic output into a conservative, stage-by-stage
 * compatibility assessment. It deliberately does not convert discovery into proof
 * that a game can render, and it never invents success for stages that were skipped.
 */
internal object ExternalOpenXrCompatibilityAssessment {
    enum class Status { VERIFIED, PARTIAL, FAILED, BLOCKED, NOT_RUN, UNKNOWN }

    enum class StageId {
        SERVICE_DISCOVERY,
        RUNTIME_BROKER,
        LOADER_INITIALIZATION,
        EXTENSION_ENUMERATION,
        INSTANCE_CREATION,
        HMD_SYSTEM,
        STEREO_CONFIGURATION,
        GRAPHICS_BINDING,
        SESSION_CREATION,
        SWAPCHAIN,
        FRAME_LOOP,
        PROJECTION_SUBMISSION,
        HAND_TRACKING,
        INPUT_BRIDGE,
        CLEANUP
    }

    data class Stage(
        val id: StageId,
        val title: String,
        val status: Status,
        val evidence: String,
        val nextStep: String? = null
    )

    data class Failure(
        val key: String,
        val result: String,
        val explanation: String,
        val stage: StageId?
    )

    data class Assessment(
        val stages: List<Stage>,
        val selectedRuntimePackage: String?,
        val highestVerifiedStage: StageId?,
        val firstFailure: Failure?,
        val warnings: List<String>,
        val recommendations: List<String>,
        val integrityReport: ExternalOpenXrReportIntegrity.Report =
            ExternalOpenXrReportIntegrity.Report(emptyList()),
        val firstFailureDiagnosis: ExternalOpenXrFailureCatalog.Diagnosis? = null,
        val recoveryPlan: ExternalOpenXrRecoveryAdvisor.Plan? = null
    ) {
        val verifiedCount: Int get() = stages.count { it.status == Status.VERIFIED }
        val failedCount: Int get() = stages.count { it.status == Status.FAILED }
        val blockedCount: Int get() = stages.count { it.status == Status.BLOCKED }
        val notRunCount: Int get() = stages.count { it.status == Status.NOT_RUN }

        fun stage(id: StageId): Stage = stages.first { it.id == id }

        fun toDisplayText(): String = buildString {
            appendLine("EXTERNAL OPENXR COMPATIBILITY ASSESSMENT")
            appendLine("assessment_version=1")
            appendLine("verified_stages=$verifiedCount")
            appendLine("failed_stages=$failedCount")
            appendLine("blocked_stages=$blockedCount")
            appendLine("not_run_stages=$notRunCount")
            appendLine("highest_verified_stage=${highestVerifiedStage?.name ?: "NONE"}")
            appendLine("selected_runtime_package=${selectedRuntimePackage ?: "UNKNOWN"}")
            appendLine("first_failure=${firstFailure?.let { it.key + " " + it.result } ?: "NONE_RECORDED"}")
            appendLine()
            stages.forEach { stage ->
                appendLine("[${stage.status}] ${stage.id.name} — ${stage.title}")
                appendLine("  evidence=${stage.evidence}")
                stage.nextStep?.let { appendLine("  next_step=$it") }
            }
            if (firstFailure != null) {
                appendLine()
                appendLine("FIRST FAILURE")
                appendLine("key=${firstFailure.key}")
                appendLine("result=${firstFailure.result}")
                appendLine("explanation=${firstFailure.explanation}")
            }
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("WARNINGS")
                warnings.forEach { appendLine("- $it") }
            }
            if (recommendations.isNotEmpty()) {
                appendLine()
                appendLine("RECOMMENDED NEXT STEPS")
                recommendations.forEachIndexed { index, item -> appendLine("${index + 1}. $item") }
            }
            appendLine()
            appendLine(integrityReport.toDisplayText())
            firstFailureDiagnosis?.let {
                appendLine()
                appendLine(it.toDisplayText())
            }
            recoveryPlan?.let {
                appendLine()
                appendLine(it.toDisplayText())
            }
            appendLine()
            append("Scope: this assessment describes the diagnostic probe only. It does not certify OpenXR conformance, arbitrary Quest APK compatibility, Meta service availability, or sustained frame performance.")
        }

        fun toJson(): String = buildString {
            append('{')
            append("\"assessment_version\":1,")
            append("\"selected_runtime_package\":").append(ExternalOpenXrCompatibilityAssessment.json(selectedRuntimePackage)).append(',')
            append("\"highest_verified_stage\":").append(ExternalOpenXrCompatibilityAssessment.json(highestVerifiedStage?.name)).append(',')
            append("\"verified_stages\":").append(verifiedCount).append(',')
            append("\"failed_stages\":").append(failedCount).append(',')
            append("\"blocked_stages\":").append(blockedCount).append(',')
            append("\"first_failure\":")
            if (firstFailure == null) append("null") else {
                append('{')
                append("\"key\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailure.key)).append(',')
                append("\"result\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailure.result)).append(',')
                append("\"explanation\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailure.explanation)).append(',')
                append("\"stage\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailure.stage?.name))
                append('}')
            }
            append(",\"stages\":[")
            stages.forEachIndexed { index, stage ->
                if (index > 0) append(',')
                append('{')
                append("\"id\":").append(ExternalOpenXrCompatibilityAssessment.json(stage.id.name)).append(',')
                append("\"title\":").append(ExternalOpenXrCompatibilityAssessment.json(stage.title)).append(',')
                append("\"status\":").append(ExternalOpenXrCompatibilityAssessment.json(stage.status.name)).append(',')
                append("\"evidence\":").append(ExternalOpenXrCompatibilityAssessment.json(stage.evidence)).append(',')
                append("\"next_step\":").append(ExternalOpenXrCompatibilityAssessment.json(stage.nextStep))
                append('}')
            }
            append("],\"warnings\":")
            appendJsonArray(warnings)
            append(",\"recommendations\":")
            appendJsonArray(recommendations)
            append(",\"integrity_report\":").append(integrityReport.toJson())
            append(",\"first_failure_diagnosis\":")
            if (firstFailureDiagnosis == null) {
                append("null")
            } else {
                append('{')
                append("\"code\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailureDiagnosis.code)).append(',')
                append("\"category\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailureDiagnosis.category.name)).append(',')
                append("\"impact\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailureDiagnosis.impact.name)).append(',')
                append("\"summary\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailureDiagnosis.summary)).append(',')
                append("\"likely_cause\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailureDiagnosis.likelyCause)).append(',')
                append("\"stage_hint\":").append(ExternalOpenXrCompatibilityAssessment.json(firstFailureDiagnosis.stageHint?.name))
                append('}')
            }
            append(",\"recovery_plan\":").append(recoveryPlan?.toJson() ?: "null")
            append('}')
        }

        private fun StringBuilder.appendJsonArray(values: List<String>) {
            append('[')
            values.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append(ExternalOpenXrCompatibilityAssessment.json(value))
            }
            append(']')
        }
    }

    private data class ReportFields(
        val serviceLines: List<String>,
        val loaderLines: List<String>,
        val serviceValues: Map<String, String>,
        val loaderValues: Map<String, String>
    ) {
        fun loader(key: String): String? = loaderValues[key]
        fun serviceLine(prefix: String): String? = serviceLines.firstOrNull { it.startsWith(prefix) }
        fun loaderLine(prefix: String): String? = loaderLines.firstOrNull { it.startsWith(prefix) }
    }

    fun analyze(serviceReport: String, loaderReport: String): Assessment {
        val fields = parse(serviceReport, loaderReport)
        val warnings = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        val selectedPackage = selectedRuntime(fields)
        val stages = mutableListOf<Stage>()

        stages += serviceStage(fields)
        stages += brokerStage(fields, selectedPackage, warnings, recommendations)
        stages += loaderStage(fields, warnings, recommendations)
        stages += extensionStage(fields, recommendations)
        stages += instanceStage(fields, recommendations)
        stages += systemStage(fields, recommendations)
        stages += stereoStage(fields, recommendations)
        stages += graphicsStage(fields, recommendations)
        stages += sessionStage(fields, recommendations)
        stages += swapchainStage(fields, recommendations)
        stages += frameStage(fields, recommendations)
        stages += projectionStage(fields, recommendations)
        stages += handTrackingStage(fields, recommendations)
        stages += inputBridgeStage(fields, recommendations)
        stages += cleanupStage(fields, warnings)

        val firstFailure = findFirstFailure(fields)
        val integrityReport = ExternalOpenXrReportIntegrity.analyze(serviceReport, loaderReport)
        val firstFailureDiagnosis = firstFailure?.let {
            ExternalOpenXrFailureCatalog.diagnose(it.result, it.key, it.stage)
        }
        val recoveryPlan = ExternalOpenXrRecoveryAdvisor.build(
            stages = stages,
            firstFailure = firstFailure,
            diagnosis = firstFailureDiagnosis,
            integrity = integrityReport
        )
        if (integrityReport.blockingCount > 0 || integrityReport.highCount > 0) {
            warnings += "Cross-report integrity checks found " +
                (integrityReport.blockingCount + integrityReport.highCount) +
                " high-impact contradiction(s); review OPENXR REPORT INTEGRITY below."
            integrityReport.findings.firstOrNull {
                it.severity == ExternalOpenXrReportIntegrity.Severity.BLOCKING ||
                    it.severity == ExternalOpenXrReportIntegrity.Severity.HIGH
            }?.let { recommendations.add(0, it.recommendation) }
        }
        if (firstFailure != null && recommendations.none { it.contains(firstFailure.key) }) {
            recommendations.add(0, "Inspect ${firstFailure.key}: ${firstFailure.explanation}")
        }
        if (stages.any { it.status == Status.UNKNOWN }) {
            warnings += "Some report fields were absent; missing evidence is shown as UNKNOWN, not as success."
        }
        if (stages.any { it.status == Status.NOT_RUN || it.status == Status.BLOCKED }) {
            warnings += "Later stages may be untested because an earlier prerequisite was unavailable."
        }
        val highest = stages.lastOrNull { it.status == Status.VERIFIED }?.id
        return Assessment(
            stages = stages,
            selectedRuntimePackage = selectedPackage,
            highestVerifiedStage = highest,
            firstFailure = firstFailure,
            warnings = warnings.distinct(),
            recommendations = recommendations.distinct(),
            integrityReport = integrityReport,
            firstFailureDiagnosis = firstFailureDiagnosis,
            recoveryPlan = recoveryPlan
        )
    }

    private fun parse(service: String, loader: String): ReportFields {
        val serviceLines = service.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val loaderLines = loader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return ReportFields(
            serviceLines = serviceLines,
            loaderLines = loaderLines,
            serviceValues = keyValues(serviceLines),
            loaderValues = keyValues(loaderLines)
        )
    }

    private fun keyValues(lines: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        lines.forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (!KEY_PATTERN.matches(key)) return@forEach
            result[key] = line.substring(separator + 1).trim()
        }
        return result
    }

    private fun selectedRuntime(fields: ReportFields): String? {
        val installable = fields.serviceLine("Installable RuntimeBroker:").orEmpty()
        val system = fields.serviceLine("System RuntimeBroker:").orEmpty()
        return activePackage(installable) ?: activePackage(system)
    }

    private fun activePackage(line: String): String? =
        ACTIVE_PACKAGE_PATTERN.find(line)?.groupValues?.getOrNull(1)

    private fun serviceStage(fields: ReportFields): Stage {
        val visible = fields.serviceLine("EndXR service visible:")
        val exported = fields.serviceLine("EndXR service exported:")
        val library = fields.serviceLine("Runtime library exists:")
        return when {
            visible == null || exported == null || library == null ->
                stage(StageId.SERVICE_DISCOVERY, "Android runtime service discovery", Status.UNKNOWN,
                    "Service visibility/export/library evidence is incomplete.",
                    "Install the current NextVRera APK and rerun the separate-package probe.")
            visible.endsWith("NO") ->
                stage(StageId.SERVICE_DISCOVERY, "Android runtime service discovery", Status.FAILED,
                    "The external package cannot discover the EndXR runtime service.",
                    "Check the exported OpenXR runtime service and Android package visibility.")
            exported.endsWith("NO") ->
                stage(StageId.SERVICE_DISCOVERY, "Android runtime service discovery", Status.FAILED,
                    "The EndXR service is visible but is not exported.",
                    "Check the runtime service declaration in AndroidManifest.xml.")
            library.endsWith("NO") ->
                stage(StageId.SERVICE_DISCOVERY, "Android runtime service discovery", Status.FAILED,
                    "The declared native runtime library is not visible at the reported path.",
                    "Check ABI packaging, SoFilename metadata and nativeLibraryDir.")
            visible.endsWith("YES") && exported.endsWith("YES") && library.endsWith("YES") ->
                stage(StageId.SERVICE_DISCOVERY, "Android runtime service discovery", Status.VERIFIED,
                    "The external package sees an exported EndXR service and its native library.")
            else -> stage(StageId.SERVICE_DISCOVERY, "Android runtime service discovery", Status.UNKNOWN,
                "The service report contains unrecognized values.")
        }
    }

    private fun brokerStage(
        fields: ReportFields,
        selectedPackage: String?,
        warnings: MutableList<String>,
        recommendations: MutableList<String>
    ): Stage {
        val installable = fields.serviceLine("Installable RuntimeBroker:").orEmpty()
        val system = fields.serviceLine("System RuntimeBroker:").orEmpty()
        if (installable.isBlank() && system.isBlank()) {
            return stage(StageId.RUNTIME_BROKER, "Android RuntimeBroker selection", Status.UNKNOWN,
                "No installable or system broker response was included.")
        }
        if (selectedPackage != null) {
            val selectedLine = if (installable.contains("active package=$selectedPackage")) installable else system
            val libraryExists = selectedLine.contains("exists=true")
            if (selectedPackage == ENDXR_PACKAGE && libraryExists) {
                return stage(StageId.RUNTIME_BROKER, "Android RuntimeBroker selection", Status.VERIFIED,
                    "A broker reports EndXR as active and the selected native library exists.")
            }
            if (!libraryExists) {
                warnings += "The broker reports an active package, but its selected library does not exist."
                recommendations += "Check the selected runtime's ABI-specific native library path and filename."
            } else {
                warnings += "The broker currently reports $selectedPackage rather than EndXR."
                recommendations += "If EndXR is intended to be active, inspect Android RuntimeBroker registration and user/vendor selection policy."
            }
            return stage(StageId.RUNTIME_BROKER, "Android RuntimeBroker selection", Status.PARTIAL,
                "A runtime is selected ($selectedPackage), but it is not a verified, loadable EndXR selection.")
        }
        val allUnavailable = listOf(installable, system).all {
            it.contains("provider not installed") || it.contains("provider not visible")
        }
        val queryFailure = listOf(installable, system).any {
            it.contains("query failed") || it.contains("query returned null")
        }
        return when {
            allUnavailable -> stage(StageId.RUNTIME_BROKER, "Android RuntimeBroker selection", Status.BLOCKED,
                "Neither RuntimeBroker provider is installed or visible.",
                "Use a device/build with a supported Android Khronos RuntimeBroker; service discovery alone cannot select a runtime.")
            queryFailure -> stage(StageId.RUNTIME_BROKER, "Android RuntimeBroker selection", Status.FAILED,
                "A RuntimeBroker provider was found, but its active-runtime query failed.",
                "Inspect provider permissions, ABI path and query exception details.")
            else -> stage(StageId.RUNTIME_BROKER, "Android RuntimeBroker selection", Status.BLOCKED,
                "No active runtime package was returned by the visible broker provider(s).",
                "Check whether the device has an active runtime selection and whether the broker exposes the expected OpenXR schema.")
        }
    }

    private fun loaderStage(
        fields: ReportFields,
        warnings: MutableList<String>,
        recommendations: MutableList<String>
    ): Stage {
        val result = fields.loader("xrInitializeLoaderKHR call")
        return when {
            result == null -> stage(StageId.LOADER_INITIALIZATION, "Khronos loader initialization", Status.UNKNOWN,
                "The loader initialization result was not recorded.")
            result.startsWith("XR_SUCCESS") -> stage(StageId.LOADER_INITIALIZATION, "Khronos loader initialization", Status.VERIFIED,
                "The official Khronos Android loader initialized with the Activity context.")
            else -> {
                warnings += "The official loader did not initialize successfully: $result"
                recommendations += "Check the exact xrInitializeLoaderKHR result and Android Activity context lifetime."
                stage(StageId.LOADER_INITIALIZATION, "Khronos loader initialization", Status.FAILED,
                    "xrInitializeLoaderKHR returned $result.",
                    "Check XR_KHR_android_create_instance setup and pass the live Activity/JavaVM.")
            }
        }
    }

    private fun extensionStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val result = fields.loader("xrEnumerateInstanceExtensionProperties(list)")
            ?: fields.loader("xrEnumerateInstanceExtensionProperties(count)")
        return resultStage(StageId.EXTENSION_ENUMERATION, "Runtime extension enumeration", result,
            "The official loader enumerated runtime extensions.",
            "Inspect runtime discovery and extension enumeration errors.",
            recommendations)
    }

    private fun instanceStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val result = fields.loader("xrCreateInstance")
        val created = fields.loader("runtime_instance_created")
        return when {
            result == null || created == null -> stage(StageId.INSTANCE_CREATION, "OpenXR instance creation", Status.UNKNOWN,
                "xrCreateInstance or runtime_instance_created evidence is missing.")
            result.startsWith("XR_SUCCESS") && created == "YES" ->
                stage(StageId.INSTANCE_CREATION, "OpenXR instance creation", Status.VERIFIED,
                    "xrCreateInstance succeeded in the separate client package.")
            result.startsWith("XR_ERROR_RUNTIME_UNAVAILABLE") ->
                stage(StageId.INSTANCE_CREATION, "OpenXR instance creation", Status.BLOCKED,
                    "The loader could not obtain an active runtime (XR_ERROR_RUNTIME_UNAVAILABLE).",
                    "Resolve RuntimeBroker selection first; service discovery is not active runtime selection.")
            else -> stage(StageId.INSTANCE_CREATION, "OpenXR instance creation", Status.FAILED,
                "xrCreateInstance returned $result; runtime_instance_created=$created.",
                "Check the exact loader result, API version, requested extensions and selected runtime.")
        }
    }

    private fun systemStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val result = fields.loader("xrGetSystem(HMD)")
        if (result == null && fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.HMD_SYSTEM, "HMD system discovery", Status.NOT_RUN,
                "No runtime instance existed, so HMD system discovery was not attempted.")
        }
        return resultStage(StageId.HMD_SYSTEM, "HMD system discovery", result,
            "The runtime returned an HMD system identifier.",
            "Inspect form-factor availability and runtime system support.",
            recommendations)
    }

    private fun stereoStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val supported = fields.loader("primary_stereo_supported")
        if (supported == null && fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.STEREO_CONFIGURATION, "Primary stereo view configuration", Status.NOT_RUN,
                "No runtime instance existed, so view configuration enumeration was not attempted.")
        }
        return when (supported) {
            "YES" -> stage(StageId.STEREO_CONFIGURATION, "Primary stereo view configuration", Status.VERIFIED,
                "The runtime reports PRIMARY_STEREO support.")
            "NO" -> stage(StageId.STEREO_CONFIGURATION, "Primary stereo view configuration", Status.FAILED,
                "The runtime did not report PRIMARY_STEREO support.",
                "Implement or expose the required primary stereo view configuration.")
            null -> stage(StageId.STEREO_CONFIGURATION, "Primary stereo view configuration", Status.UNKNOWN,
                "No primary_stereo_supported field was recorded.")
            else -> stage(StageId.STEREO_CONFIGURATION, "Primary stereo view configuration", Status.UNKNOWN,
                "Unrecognized primary_stereo_supported value: $supported.")
        }
    }

    private fun graphicsStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        if (fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.GRAPHICS_BINDING, "OpenGL ES graphics binding", Status.NOT_RUN,
                "No runtime instance existed, so EGL/OpenXR graphics binding was not attempted.")
        }
        val egl = fields.loader("external_egl_context")
        val requirements = fields.loader("xrGetOpenGLESGraphicsRequirementsKHR")
        val result = when {
            egl?.contains("PASS") == true && requirements?.startsWith("XR_SUCCESS") == true -> Status.VERIFIED
            egl?.contains("FAIL") == true || requirements?.startsWith("XR_ERROR") == true -> Status.FAILED
            fields.loader("external_gles_session")?.contains("SKIPPED") == true -> Status.NOT_RUN
            egl == null && requirements == null -> Status.UNKNOWN
            else -> Status.PARTIAL
        }
        val evidence = "egl_context=${egl ?: "not recorded"}; graphics_requirements=${requirements ?: "not recorded"}"
        val next = if (result == Status.FAILED) "Check EGL config, GLES version and runtime graphics requirements." else null
        if (result == Status.FAILED) recommendations += next.orEmpty()
        return stage(StageId.GRAPHICS_BINDING, "OpenGL ES graphics binding", result, evidence, next)
    }

    private fun sessionStage(fields: ReportFields, recommendations: MutableList<String>): Stage =
        resultStage(
            StageId.SESSION_CREATION,
            "OpenXR GLES session creation",
            fields.loader("external_xrCreateSession"),
            "The external client created an OpenXR session.",
            "Inspect graphics binding, system identifier and session-create validation errors.",
            recommendations,
            prerequisite = fields.loader("runtime_instance_created") == "NO" ||
                fields.loader("external_gles_session")?.contains("SKIPPED") == true
        )

    private fun swapchainStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val create = fields.loader("external_xrCreateSwapchain")
        if (fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.SWAPCHAIN, "Swapchain creation and image enumeration", Status.NOT_RUN,
                "No runtime instance existed, so no swapchain could be created.")
        }
        val images = fields.loader("external_xrEnumerateSwapchainImages")
        return when {
            create?.startsWith("XR_SUCCESS") == true && images?.startsWith("XR_SUCCESS") == true ->
                stage(StageId.SWAPCHAIN, "Swapchain creation and image enumeration", Status.VERIFIED,
                    "Swapchain creation and image enumeration succeeded.")
            create?.startsWith("XR_ERROR") == true || images?.startsWith("XR_ERROR") == true ->
                stage(StageId.SWAPCHAIN, "Swapchain creation and image enumeration", Status.FAILED,
                    "Swapchain stage failed: create=$create; images=$images.",
                    "Check supported formats, dimensions, sample count and GLES image usage.")
            create?.contains("SKIPPED") == true || fields.loader("external_gles_session")?.contains("SKIPPED") == true ->
                stage(StageId.SWAPCHAIN, "Swapchain creation and image enumeration", Status.NOT_RUN,
                    "The session/swapchain prerequisite was not reached.")
            create == null && images == null ->
                stage(StageId.SWAPCHAIN, "Swapchain creation and image enumeration", Status.UNKNOWN,
                    "No swapchain evidence was recorded.")
            else -> {
                recommendations += "Inspect swapchain create/enumerate results and ensure all acquired images are released."
                stage(StageId.SWAPCHAIN, "Swapchain creation and image enumeration", Status.PARTIAL,
                    "Swapchain evidence is incomplete: create=$create; images=$images.")
            }
        }
    }

    private fun frameStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val wait = fields.loader("external_xrWaitFrame")
        if (fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.FRAME_LOOP, "Frame lifecycle", Status.NOT_RUN,
                "No runtime instance existed, so the frame loop was not entered.")
        }
        val begin = fields.loader("external_xrBeginFrame")
        val end = fields.loader("external_xrEndFrame(projection)")
            ?: fields.loader("external_xrEndFrame(empty_fallback)")
        return when {
            wait?.startsWith("XR_SUCCESS") == true && begin?.startsWith("XR_SUCCESS") == true &&
                end?.startsWith("XR_SUCCESS") == true ->
                stage(StageId.FRAME_LOOP, "Frame lifecycle", Status.VERIFIED,
                    "WaitFrame, BeginFrame and EndFrame returned XR_SUCCESS.")
            listOf(wait, begin, end).any { it?.startsWith("XR_ERROR") == true } ->
                stage(StageId.FRAME_LOOP, "Frame lifecycle", Status.FAILED,
                    "Frame lifecycle returned an OpenXR error: wait=$wait; begin=$begin; end=$end.",
                    "Check session state, frame call order and exactly-once BeginFrame/EndFrame pairing.")
            fields.loader("external_frame_lifecycle")?.contains("SKIPPED") == true ||
                fields.loader("external_gles_session")?.contains("SKIPPED") == true ->
                stage(StageId.FRAME_LOOP, "Frame lifecycle", Status.NOT_RUN,
                    "The session or graphics prerequisites were not reached.")
            wait == null && begin == null && end == null ->
                stage(StageId.FRAME_LOOP, "Frame lifecycle", Status.UNKNOWN,
                    "No frame lifecycle results were recorded.")
            else -> stage(StageId.FRAME_LOOP, "Frame lifecycle", Status.PARTIAL,
                "Frame lifecycle evidence is incomplete: wait=$wait; begin=$begin; end=$end.",
                "Rerun the probe and inspect the first missing or failed frame call.")
        }
    }

    private fun projectionStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val submitted = fields.loader("external_projection_layer_submission")
        if (fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.NOT_RUN,
                "No runtime instance existed, so projection submission was not attempted.")
        }
        val readback = fields.loader("external_projection_swapchain_image_verification")
        return when {
            submitted == "PASS" && readback == "PASS" ->
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.VERIFIED,
                    "The runtime accepted a projection layer and the swapchain image readback matched the test pattern.")
            submitted == "PASS" && readback == null ->
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.PARTIAL,
                    "The runtime accepted a projection layer; pixel readback evidence is absent.")
            submitted == "PASS" && readback == "FAIL" ->
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.PARTIAL,
                    "Projection submission succeeded but swapchain image verification failed.",
                    "Inspect GL readback errors, framebuffer completeness and left/right image regions.")
            submitted == "NOT_SUBMITTED" || submitted?.startsWith("REJECTED") == true ->
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.FAILED,
                    "Projection layer was not accepted: $submitted.",
                    "Inspect located stereo views, image acquire/wait/release ordering and xrEndFrame.")
            submitted == null && fields.loader("external_frame_lifecycle")?.contains("SKIPPED") == true ->
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.NOT_RUN,
                    "Frame lifecycle was skipped before projection submission.")
            submitted == null ->
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.UNKNOWN,
                    "No projection submission evidence was recorded.")
            else -> {
                recommendations += "Inspect external projection submission and swapchain readback evidence."
                stage(StageId.PROJECTION_SUBMISSION, "Stereo projection submission and readback", Status.PARTIAL,
                    "Projection evidence is incomplete: submission=$submitted; readback=$readback.")
            }
        }
    }

    private fun handTrackingStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val advertised = fields.loader("runtime_advertises_XR_EXT_hand_tracking")
        val left = fields.loader("external_xrCreateHandTrackerEXT_left")
        val right = fields.loader("external_xrCreateHandTrackerEXT_right")
        val leftLocate = fields.loader("external_xrLocateHandJointsEXT_left")
        val rightLocate = fields.loader("external_xrLocateHandJointsEXT_right")
        return when {
            advertised == "NO" -> stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.BLOCKED,
                "The active runtime does not advertise XR_EXT_hand_tracking.",
                "Hand tracking cannot be tested through this extension until the runtime advertises it.")
            advertised == "YES" && left?.startsWith("XR_SUCCESS") == true &&
                right?.startsWith("XR_SUCCESS") == true &&
                leftLocate?.startsWith("XR_SUCCESS") == true &&
                rightLocate?.startsWith("XR_SUCCESS") == true ->
                stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.VERIFIED,
                    "Both hand trackers were created and both locate calls succeeded.")
            advertised == "YES" && listOf(left, right, leftLocate, rightLocate).any { it?.startsWith("XR_ERROR") == true } ->
                stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.FAILED,
                    "The extension is advertised, but one or more tracker operations failed.",
                    "Inspect per-hand create/locate results and ensure the session is running.")
            advertised == "YES" ->
                stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.PARTIAL,
                    "The extension is advertised, but both hand trackers/locate results were not fully verified.",
                    "Run the probe with an active session and inspect left/right tracker operations.")
            advertised == null && fields.loader("runtime_instance_created") == "NO" ->
                stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.NOT_RUN,
                    "No runtime instance was created; extension capability could not be queried.")
            advertised == null ->
                stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.UNKNOWN,
                    "The report did not include XR_EXT_hand_tracking capability evidence.")
            else -> stage(StageId.HAND_TRACKING, "OpenXR hand tracking extension", Status.UNKNOWN,
                "Unrecognized extension advertisement value: $advertised.")
        }
    }

    private fun inputBridgeStage(fields: ReportFields, recommendations: MutableList<String>): Stage {
        val line = fields.serviceLine("EndXR input IPC:").orEmpty()
        return when {
            line.contains("provider_query=PASS") && line.contains("provider_permission=GRANTED") ->
                stage(StageId.INPUT_BRIDGE, "Cross-process input snapshot", Status.VERIFIED,
                    "The separate package read the signature-protected input snapshot.")
            line.contains("provider_query=FAIL") || line.contains("permission=DENIED") ->
                stage(StageId.INPUT_BRIDGE, "Cross-process input snapshot", Status.FAILED,
                    line.removePrefix("EndXR input IPC:").trim(),
                    "Check explicit package approval, signing certificate and provider read permission.")
            line.contains("provider not visible") ->
                stage(StageId.INPUT_BRIDGE, "Cross-process input snapshot", Status.BLOCKED,
                    "The EndXR input provider is not visible to the external client.",
                    "Check provider export/visibility and the diagnostic APK package queries.")
            line.isBlank() ->
                stage(StageId.INPUT_BRIDGE, "Cross-process input snapshot", Status.UNKNOWN,
                    "No external input provider result was recorded.")
            else -> {
                recommendations += "Inspect the EndXR input IPC status and distinguish permission, provider and freshness failures."
                stage(StageId.INPUT_BRIDGE, "Cross-process input snapshot", Status.PARTIAL,
                    line.removePrefix("EndXR input IPC:").trim())
            }
        }
    }

    private fun cleanupStage(fields: ReportFields, warnings: MutableList<String>): Stage {
        val instance = fields.loader("xrDestroyInstance")
        if (instance == null && fields.loader("runtime_instance_created") == "NO") {
            return stage(StageId.CLEANUP, "OpenXR resource cleanup", Status.NOT_RUN,
                "No OpenXR instance or session was created, so no OpenXR handles required teardown.")
        }
        val session = fields.loader("external_xrDestroySession")
        val hasSession = fields.loader("external_xrCreateSession")?.startsWith("XR_SUCCESS") == true
        return when {
            instance?.startsWith("XR_SUCCESS") == true &&
                (!hasSession || session?.startsWith("XR_SUCCESS") == true) ->
                stage(StageId.CLEANUP, "OpenXR resource cleanup", Status.VERIFIED,
                    "Created OpenXR instance/session handles were destroyed successfully.")
            instance?.startsWith("XR_ERROR") == true || session?.startsWith("XR_ERROR") == true -> {
                warnings += "One or more OpenXR resources did not report successful destruction."
                stage(StageId.CLEANUP, "OpenXR resource cleanup", Status.FAILED,
                    "instance_destroy=$instance; session_destroy=$session.",
                    "Check teardown ordering and destroy every successfully created handle exactly once.")
            }
            instance == null ->
                stage(StageId.CLEANUP, "OpenXR resource cleanup", Status.UNKNOWN,
                    "The instance destruction result was not recorded.")
            else -> stage(StageId.CLEANUP, "OpenXR resource cleanup", Status.PARTIAL,
                "Cleanup evidence is incomplete: instance_destroy=$instance; session_destroy=$session.")
        }
    }

    private fun resultStage(
        id: StageId,
        title: String,
        result: String?,
        successEvidence: String,
        failureNextStep: String,
        recommendations: MutableList<String>,
        prerequisite: Boolean = false
    ): Stage = when {
        result == null && prerequisite ->
            stage(id, title, Status.NOT_RUN, "A required earlier stage was skipped.")
        result == null ->
            stage(id, title, Status.UNKNOWN, "No result was recorded for this stage.")
        result.startsWith("XR_SUCCESS") ->
            stage(id, title, Status.VERIFIED, successEvidence)
        result.startsWith("XR_ERROR_RUNTIME_UNAVAILABLE") ->
            stage(id, title, Status.BLOCKED, "Runtime unavailable: $result", failureNextStep)
        result.startsWith("XR_ERROR") -> {
            recommendations += failureNextStep
            stage(id, title, Status.FAILED, "$result", failureNextStep)
        }
        result.contains("SKIPPED") ->
            stage(id, title, Status.NOT_RUN, result, failureNextStep)
        else -> stage(id, title, Status.PARTIAL, "Unrecognized or incomplete result: $result", failureNextStep)
    }

    private fun findFirstFailure(fields: ReportFields): Failure? {
        val keys = listOf(
            "xrInitializeLoaderKHR call",
            "xrEnumerateInstanceExtensionProperties(list)",
            "xrEnumerateInstanceExtensionProperties(count)",
            "xrCreateInstance",
            "xrGetInstanceProperties",
            "xrGetSystem(HMD)",
            "xrGetSystemProperties",
            "xrEnumerateViewConfigurations(list)",
            "external_egl_initialize",
            "external_egl_context",
            "xrGetOpenGLESGraphicsRequirementsKHR",
            "external_xrCreateSession",
            "external_xrCreateSwapchain",
            "external_xrEnumerateSwapchainImages",
            "external_xrWaitFrame",
            "external_xrBeginFrame",
            "external_xrAcquireSwapchainImage",
            "external_xrWaitSwapchainImage",
            "external_xrReleaseSwapchainImage",
            "external_xrEndFrame(projection)",
            "xrDestroyInstance"
        )
        keys.forEach { key ->
            val value = fields.loader(key) ?: return@forEach
            if (value.startsWith("XR_ERROR") || value.contains("FAIL")) {
                return Failure(key, value, explainFailure(value), stageForKey(key))
            }
        }
        return null
    }

    private fun stageForKey(key: String): StageId = when {
        key.startsWith("xrInitialize") -> StageId.LOADER_INITIALIZATION
        key.startsWith("xrEnumerateInstanceExtension") -> StageId.EXTENSION_ENUMERATION
        key == "xrCreateInstance" -> StageId.INSTANCE_CREATION
        key.contains("GetSystem") -> StageId.HMD_SYSTEM
        key.contains("ViewConfiguration") -> StageId.STEREO_CONFIGURATION
        key.contains("egl") || key.contains("GraphicsRequirements") -> StageId.GRAPHICS_BINDING
        key.contains("CreateSession") -> StageId.SESSION_CREATION
        key.contains("Swapchain") -> StageId.SWAPCHAIN
        key.contains("EndFrame") -> StageId.PROJECTION_SUBMISSION
        key.contains("Frame") || key.contains("SwapchainImage") -> StageId.FRAME_LOOP
        else -> StageId.CLEANUP
    }

    private fun explainFailure(result: String): String = when {
        result.contains("XR_ERROR_RUNTIME_UNAVAILABLE") -> "No active runtime was returned by Android RuntimeBroker."
        result.contains("XR_ERROR_API_VERSION_UNSUPPORTED") -> "The runtime rejected the requested OpenXR API version."
        result.contains("XR_ERROR_EXTENSION_NOT_PRESENT") -> "A requested OpenXR extension is not available."
        result.contains("XR_ERROR_FORM_FACTOR_UNSUPPORTED") -> "The runtime does not support the requested HMD form factor."
        result.contains("XR_ERROR_FORM_FACTOR_UNAVAILABLE") -> "The requested HMD form factor is currently unavailable."
        result.contains("XR_ERROR_GRAPHICS_DEVICE_INVALID") -> "The supplied graphics device does not match the runtime's graphics requirements."
        result.contains("XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED") -> "The selected swapchain format is not supported."
        result.contains("XR_ERROR_SWAPCHAIN_RECT_INVALID") -> "The submitted swapchain rectangle is invalid."
        result.contains("XR_ERROR_SESSION_LOST") -> "The OpenXR session was lost."
        result.contains("XR_ERROR_INSTANCE_LOST") -> "The OpenXR instance was lost."
        result.contains("XR_ERROR_CALL_ORDER_INVALID") -> "OpenXR calls were made in an invalid lifecycle order."
        result.contains("XR_ERROR_INITIALIZATION_FAILED") -> "Runtime negotiation or initialization failed."
        result.contains("FAIL") -> "A native or EGL diagnostic step reported failure."
        else -> "Inspect the exact result code and the preceding successful stage."
    }

    private fun stage(
        id: StageId,
        title: String,
        status: Status,
        evidence: String,
        nextStep: String? = null
    ) = Stage(id, title, status, evidence, nextStep)

    private fun json(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u${char.code.toString(16).padStart(4, '0')}")
                    else append(char)
                }
            }
            append('"')
        }
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9_(). -]+")
    private val ACTIVE_PACKAGE_PATTERN = Regex("active package=([A-Za-z0-9_.]+)")
    private const val ENDXR_PACKAGE = "com.noyclic.questphonexr"
}
