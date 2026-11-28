package com.noyclic.xrclientprobe

/**
 * Cross-checks the service and loader reports before their evidence is summarized.
 *
 * The analyser never changes a stage verdict. It adds integrity findings when
 * different fields disagree or when a later successful call has no plausible
 * prerequisite. This keeps ambiguous vendor reports visible instead of silently
 * choosing whichever log line happens to look most optimistic.
 */
internal object ExternalOpenXrReportIntegrity {
    enum class Severity { BLOCKING, HIGH, WARNING, INFO }

    data class Finding(
        val code: String,
        val severity: Severity,
        val message: String,
        val evidenceKeys: List<String>,
        val recommendation: String
    )

    data class Report(val findings: List<Finding>) {
        val blockingCount: Int get() = findings.count { it.severity == Severity.BLOCKING }
        val highCount: Int get() = findings.count { it.severity == Severity.HIGH }
        val warningCount: Int get() = findings.count { it.severity == Severity.WARNING }
        val infoCount: Int get() = findings.count { it.severity == Severity.INFO }
        val isConsistent: Boolean get() = findings.none {
            it.severity == Severity.BLOCKING || it.severity == Severity.HIGH
        }

        fun toDisplayText(): String = buildString {
            appendLine("OPENXR REPORT INTEGRITY")
            appendLine("verdict=" + if (isConsistent) "NO_HIGH_CONFLICTS_FOUND" else "CONFLICTS_FOUND")
            appendLine("blocking=" + blockingCount)
            appendLine("high=" + highCount)
            appendLine("warnings=" + warningCount)
            appendLine("info=" + infoCount)
            if (findings.isEmpty()) {
                append("No cross-field contradictions were detected in the supplied report fields.")
            } else {
                findings.forEachIndexed { index, finding ->
                    appendLine()
                    appendLine((index + 1).toString() + ". [" + finding.severity.name + "] " + finding.code)
                    appendLine("   " + finding.message)
                    if (finding.evidenceKeys.isNotEmpty()) {
                        appendLine("   evidence=" + finding.evidenceKeys.joinToString(","))
                    }
                    appendLine("   next_step=" + finding.recommendation)
                }
            }
        }

        fun toJson(): String = buildString {
            append("{")
            append("\"verdict\":\"").append(if (isConsistent) "NO_HIGH_CONFLICTS_FOUND" else "CONFLICTS_FOUND").append("\",")
            append("\"blocking\":").append(blockingCount).append(',')
            append("\"high\":").append(highCount).append(',')
            append("\"warnings\":").append(warningCount).append(',')
            append("\"info\":").append(infoCount).append(',')
            append("\"findings\":[")
            findings.forEachIndexed { index, finding ->
                if (index > 0) append(',')
                append('{')
                append("\"code\":").append(json(finding.code)).append(',')
                append("\"severity\":").append(json(finding.severity.name)).append(',')
                append("\"message\":").append(json(finding.message)).append(',')
                append("\"evidence_keys\":").append(stringArray(finding.evidenceKeys)).append(',')
                append("\"recommendation\":").append(json(finding.recommendation))
                append('}')
            }
            append("]}")
        }

        private fun stringArray(values: List<String>): String =
            values.joinToString(prefix = "[", postfix = "]") { json(it) }
    }

    private data class Observations(
        val allLines: List<String>,
        val values: Map<String, List<String>>
    ) {
        fun latest(key: String): String? = values[key]?.lastOrNull()
        fun first(key: String): String? = values[key]?.firstOrNull()
        fun has(key: String): Boolean = values.containsKey(key)
        fun entries(): Map<String, List<String>> = values
    }

    fun analyze(serviceReport: String, loaderReport: String): Report {
        val service = parse(serviceReport)
        val loader = parse(loaderReport)
        val findings = mutableListOf<Finding>()

        inspectDuplicateKeys("service", service, findings)
        inspectDuplicateKeys("loader", loader, findings)
        inspectInstanceCreation(loader, findings)
        inspectSessionAndSwapchain(loader, findings)
        inspectFrameOrder(loader, findings)
        inspectProjection(loader, findings)
        inspectHandTracking(loader, findings)
        inspectInputBridge(service, findings)
        inspectBrokerAgreement(service, findings)
        inspectLifecycleSummary(loader, findings)

        val ordered = findings.distinctBy { it.code + "|" + it.evidenceKeys.joinToString("|") }
            .sortedWith(compareBy<Finding> { it.severity.ordinal }.thenBy { it.code })
        return Report(ordered)
    }

    private fun parse(report: String): Observations {
        val bounded = report.take(MAX_REPORT_LENGTH)
        val lines = bounded.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.toList()
        val values = linkedMapOf<String, MutableList<String>>()
        lines.forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (!KEY_PATTERN.matches(key)) return@forEach
            values.getOrPut(key) { mutableListOf() }.add(line.substring(separator + 1).trim())
        }
        return Observations(lines, values)
    }

    private fun inspectDuplicateKeys(
        source: String,
        observations: Observations,
        findings: MutableList<Finding>
    ) {
        observations.entries().forEach { (key, values) ->
            val distinct = values.distinct()
            if (distinct.size <= 1) return@forEach
            val categories = distinct.map { outcome(it) }.distinct()
            val contradictory = categories.contains(Outcome.SUCCESS) &&
                (categories.contains(Outcome.FAILURE) || categories.contains(Outcome.SKIPPED))
            findings += Finding(
                code = if (contradictory) "CONFLICTING_DUPLICATE_" + source.uppercase()
                    else "CHANGING_DUPLICATE_" + source.uppercase(),
                severity = if (contradictory) Severity.HIGH else Severity.WARNING,
                message = "Key " + key + " appears " + values.size +
                    " times with different values; the stage parser currently consumes the last observation.",
                evidenceKeys = listOf(key) + distinct.take(3).map { source + ":" + it.take(80) },
                recommendation = "Keep the ordered raw observations and determine whether this is a retry, a second probe, or a stale mixed report."
            )
        }
    }

    private fun inspectInstanceCreation(loader: Observations, findings: MutableList<Finding>) {
        val create = loader.latest("xrCreateInstance")
        val created = loader.latest("runtime_instance_created")
        if (created == "YES" && isFailure(create)) {
            findings += Finding(
                "INSTANCE_CREATED_AFTER_CREATE_FAILURE", Severity.BLOCKING,
                "runtime_instance_created=YES conflicts with an xrCreateInstance error.",
                listOf("xrCreateInstance", "runtime_instance_created"),
                "Check whether both values came from the same probe attempt and preserve the original XrResult before setting the created flag."
            )
        }
        if (created == "NO" && isSuccess(create)) {
            findings += Finding(
                "INSTANCE_NOT_CREATED_AFTER_CREATE_SUCCESS", Severity.BLOCKING,
                "xrCreateInstance returned XR_SUCCESS but runtime_instance_created is NO.",
                listOf("xrCreateInstance", "runtime_instance_created"),
                "Check the code that updates the instance-created marker and whether the report combines different process attempts."
            )
        }
        if (created != null && created !in setOf("YES", "NO")) {
            findings += Finding(
                "INSTANCE_CREATED_MARKER_UNRECOGNIZED", Severity.WARNING,
                "runtime_instance_created contains an unrecognized value: " + created.take(80),
                listOf("runtime_instance_created"),
                "Use YES or NO only when the instance creation call was actually observed; otherwise omit the marker."
            )
        }
    }

    private fun inspectSessionAndSwapchain(loader: Observations, findings: MutableList<Finding>) {
        val instance = loader.latest("runtime_instance_created")
        val instanceResult = loader.latest("xrCreateInstance")
        val session = loader.latest("external_xrCreateSession")
        val swapchain = loader.latest("external_xrCreateSwapchain")
        val enumerateImages = loader.latest("external_xrEnumerateSwapchainImages")

        if (isSuccess(session) && (instance == "NO" || isFailure(instanceResult))) {
            findings += Finding(
                "SESSION_SUCCESS_WITHOUT_INSTANCE", Severity.BLOCKING,
                "Session creation is reported successful although instance creation is explicitly unavailable or failed.",
                listOf("xrCreateInstance", "runtime_instance_created", "external_xrCreateSession"),
                "Verify that all three fields belong to the same external-client run and that the instance marker is written before session creation."
            )
        }
        if (isSuccess(swapchain) && isFailure(session)) {
            findings += Finding(
                "SWAPCHAIN_SUCCESS_AFTER_SESSION_FAILURE", Severity.BLOCKING,
                "Swapchain creation is reported successful after session creation failed.",
                listOf("external_xrCreateSession", "external_xrCreateSwapchain"),
                "Check for stale swapchain output or a report assembled from multiple sessions."
            )
        }
        if (isSuccess(enumerateImages) && isFailure(swapchain)) {
            findings += Finding(
                "SWAPCHAIN_IMAGES_SUCCESS_AFTER_CREATE_FAILURE", Severity.HIGH,
                "Swapchain image enumeration is reported successful after swapchain creation failed.",
                listOf("external_xrCreateSwapchain", "external_xrEnumerateSwapchainImages"),
                "Verify that the enumerated images belong to the swapchain created in this same run."
            )
        }
        if (isSuccess(session) && instance == null) {
            findings += Finding(
                "SESSION_WITHOUT_INSTANCE_EVIDENCE", Severity.WARNING,
                "Session creation succeeded but the report omitted runtime_instance_created.",
                listOf("external_xrCreateSession", "runtime_instance_created"),
                "Include the instance marker or xrCreateInstance result so the prerequisite chain can be audited."
            )
        }
    }

    private fun inspectFrameOrder(loader: Observations, findings: MutableList<Finding>) {
        val wait = loader.latest("external_xrWaitFrame")
        val begin = loader.latest("external_xrBeginFrame")
        val endProjection = loader.latest("external_xrEndFrame(projection)")
        val endEmpty = loader.latest("external_xrEndFrame(empty_fallback)")
        val acquire = loader.latest("external_xrAcquireSwapchainImage")
        val waitImage = loader.latest("external_xrWaitSwapchainImage")
        val release = loader.latest("external_xrReleaseSwapchainImage")
        val end = endProjection ?: endEmpty

        if (isSuccess(begin) && isFailure(wait)) {
            findings += Finding(
                "BEGIN_FRAME_AFTER_WAIT_FRAME_FAILURE", Severity.BLOCKING,
                "xrBeginFrame succeeded even though the recorded xrWaitFrame result is an error.",
                listOf("external_xrWaitFrame", "external_xrBeginFrame"),
                "Check whether BeginFrame is called only after a successful WaitFrame in the same frame iteration."
            )
        }
        if (isSuccess(end) && isFailure(begin)) {
            findings += Finding(
                "END_FRAME_AFTER_BEGIN_FRAME_FAILURE", Severity.BLOCKING,
                "xrEndFrame succeeded although xrBeginFrame is recorded as failed.",
                listOf("external_xrBeginFrame", "external_xrEndFrame"),
                "Check the frame state machine and ensure the EndFrame result corresponds to the same frame attempt."
            )
        }
        if (isSuccess(begin) && wait == null) {
            findings += Finding(
                "BEGIN_FRAME_WITHOUT_WAIT_EVIDENCE", Severity.WARNING,
                "xrBeginFrame succeeded but no xrWaitFrame result was recorded.",
                listOf("external_xrWaitFrame", "external_xrBeginFrame"),
                "Record every frame wait result, including the first failed call."
            )
        }
        if (isSuccess(end) && begin == null) {
            findings += Finding(
                "END_FRAME_WITHOUT_BEGIN_EVIDENCE", Severity.WARNING,
                "xrEndFrame succeeded but no xrBeginFrame result was recorded.",
                listOf("external_xrBeginFrame", "external_xrEndFrame"),
                "Record the full frame sequence; do not infer missing calls from the final result."
            )
        }
        if (isSuccess(waitImage) && isFailure(acquire)) {
            findings += Finding(
                "WAIT_IMAGE_AFTER_ACQUIRE_FAILURE", Severity.BLOCKING,
                "Swapchain image wait succeeded although image acquisition failed.",
                listOf("external_xrAcquireSwapchainImage", "external_xrWaitSwapchainImage"),
                "Check image-index initialization and ensure xrWaitSwapchainImage uses an image acquired in this attempt."
            )
        }
        if (isSuccess(release) && isFailure(waitImage)) {
            findings += Finding(
                "RELEASE_IMAGE_AFTER_WAIT_FAILURE", Severity.HIGH,
                "Swapchain image release succeeded although the recorded image wait failed.",
                listOf("external_xrWaitSwapchainImage", "external_xrReleaseSwapchainImage"),
                "Check whether the image was actually acquired and whether the runtime permits release after the failed wait."
            )
        }
        if (isSuccess(waitImage) && acquire == null) {
            findings += Finding(
                "WAIT_IMAGE_WITHOUT_ACQUIRE_EVIDENCE", Severity.WARNING,
                "Swapchain image wait succeeded but acquisition evidence is missing.",
                listOf("external_xrAcquireSwapchainImage", "external_xrWaitSwapchainImage"),
                "Include acquire and wait results from the same image cycle."
            )
        }
    }

    private fun inspectProjection(loader: Observations, findings: MutableList<Finding>) {
        val submitted = loader.latest("external_projection_layer_submission")
        val readback = loader.latest("external_projection_swapchain_image_verification")
        val end = loader.latest("external_xrEndFrame(projection)")
        if (submitted == "PASS" && isFailure(end)) {
            findings += Finding(
                "PROJECTION_PASS_WITH_END_FRAME_ERROR", Severity.BLOCKING,
                "Projection submission says PASS while xrEndFrame(projection) returned an error.",
                listOf("external_projection_layer_submission", "external_xrEndFrame(projection)"),
                "Define PASS as runtime acceptance only when xrEndFrame returns XR_SUCCESS; retain a separate field for pre-submit validation."
            )
        }
        if (submitted == "REJECTED" || submitted?.startsWith("REJECTED_") == true) {
            if (isSuccess(end)) {
                findings += Finding(
                    "PROJECTION_REJECTED_WITH_END_FRAME_SUCCESS", Severity.HIGH,
                    "The projection diagnostic says REJECTED but xrEndFrame(projection) returned XR_SUCCESS.",
                    listOf("external_projection_layer_submission", "external_xrEndFrame(projection)"),
                    "Clarify whether REJECTED describes local validation or runtime rejection and use distinct keys for those outcomes."
                )
            }
        }
        if (readback == "PASS" && (submitted != "PASS" || isFailure(end))) {
            findings += Finding(
                "READBACK_PASS_WITHOUT_ACCEPTED_PROJECTION", Severity.BLOCKING,
                "Swapchain image readback says PASS without a consistent accepted projection submission.",
                listOf("external_projection_layer_submission", "external_projection_swapchain_image_verification", "external_xrEndFrame(projection)"),
                "Tie the readback evidence to the exact released swapchain image and submitted frame."
            )
        }
        if (readback == "PASS" && end == null) {
            findings += Finding(
                "READBACK_WITHOUT_END_FRAME_EVIDENCE", Severity.WARNING,
                "Swapchain image readback passed but the report omitted xrEndFrame(projection).",
                listOf("external_projection_swapchain_image_verification", "external_xrEndFrame(projection)"),
                "Keep image-content verification distinct from runtime/compositor acceptance."
            )
        }
    }

    private fun inspectHandTracking(loader: Observations, findings: MutableList<Finding>) {
        val advertised = loader.latest("runtime_advertises_XR_EXT_hand_tracking")
        val leftCreate = loader.latest("external_xrCreateHandTrackerEXT_left")
        val rightCreate = loader.latest("external_xrCreateHandTrackerEXT_right")
        val leftLocate = loader.latest("external_xrLocateHandJointsEXT_left")
        val rightLocate = loader.latest("external_xrLocateHandJointsEXT_right")
        if (advertised == "NO" && listOf(leftCreate, rightCreate, leftLocate, rightLocate).any { isSuccess(it) }) {
            findings += Finding(
                "HAND_TRACKING_CALL_SUCCESS_WHEN_NOT_ADVERTISED", Severity.HIGH,
                "A hand-tracking operation succeeded although XR_EXT_hand_tracking was reported as not advertised.",
                listOf("runtime_advertises_XR_EXT_hand_tracking", "external_xrCreateHandTrackerEXT_left", "external_xrCreateHandTrackerEXT_right", "external_xrLocateHandJointsEXT_left", "external_xrLocateHandJointsEXT_right"),
                "Check extension enumeration, enablement and whether the per-hand results came from the same runtime instance."
            )
        }
        if (isSuccess(leftLocate) && isFailure(leftCreate)) {
            findings += Finding(
                "LEFT_HAND_LOCATE_AFTER_CREATE_FAILURE", Severity.BLOCKING,
                "Left hand joint location succeeded after left hand tracker creation failed.",
                listOf("external_xrCreateHandTrackerEXT_left", "external_xrLocateHandJointsEXT_left"),
                "Verify that the locate call uses a valid tracker handle created during this run."
            )
        }
        if (isSuccess(rightLocate) && isFailure(rightCreate)) {
            findings += Finding(
                "RIGHT_HAND_LOCATE_AFTER_CREATE_FAILURE", Severity.BLOCKING,
                "Right hand joint location succeeded after right hand tracker creation failed.",
                listOf("external_xrCreateHandTrackerEXT_right", "external_xrLocateHandJointsEXT_right"),
                "Verify that the locate call uses a valid tracker handle created during this run."
            )
        }
    }

    private fun inspectInputBridge(service: Observations, findings: MutableList<Finding>) {
        val line = service.allLines.firstOrNull { it.startsWith("EndXR input IPC:") }.orEmpty()
        if (line.isEmpty()) return
        val queryPass = line.contains("provider_query=PASS")
        val queryFail = line.contains("provider_query=FAIL")
        val permissionGranted = line.contains("provider_permission=GRANTED")
        val permissionDenied = line.contains("provider_permission=DENIED") || line.contains("permission=DENIED")
        if (queryPass && queryFail) {
            findings += Finding(
                "INPUT_QUERY_PASS_AND_FAIL", Severity.HIGH,
                "The input IPC line contains both provider_query=PASS and provider_query=FAIL.",
                listOf("EndXR input IPC"),
                "Separate multiple provider attempts into ordered, individually named fields."
            )
        }
        if (permissionGranted && permissionDenied) {
            findings += Finding(
                "INPUT_PERMISSION_GRANTED_AND_DENIED", Severity.HIGH,
                "The input IPC line contains both granted and denied permission evidence.",
                listOf("EndXR input IPC"),
                "Record the permission result for the selected package and signer in a single attempt."
            )
        }
        if (queryPass && permissionDenied) {
            findings += Finding(
                "INPUT_QUERY_PASS_WITH_PERMISSION_DENIED", Severity.HIGH,
                "The provider query is reported as successful while permission is denied.",
                listOf("EndXR input IPC"),
                "Confirm whether the query ran with the external client's identity or a privileged in-process context."
            )
        }
    }

    private fun inspectBrokerAgreement(service: Observations, findings: MutableList<Finding>) {
        val installable = service.latest("Installable RuntimeBroker").orEmpty()
        val system = service.latest("System RuntimeBroker").orEmpty()
        val installablePackage = ACTIVE_PACKAGE.find(installable)?.groupValues?.getOrNull(1)
        val systemPackage = ACTIVE_PACKAGE.find(system)?.groupValues?.getOrNull(1)
        if (installablePackage != null && systemPackage != null && installablePackage != systemPackage) {
            findings += Finding(
                "BROKER_PROVIDERS_DISAGREE", Severity.WARNING,
                "Installable and system RuntimeBroker providers report different active runtime packages.",
                listOf("Installable RuntimeBroker", "System RuntimeBroker"),
                "Use the loader's selected-provider precedence to determine the runtime actually used; do not assume both providers control the same selection."
            )
        }
    }

    private fun inspectLifecycleSummary(loader: Observations, findings: MutableList<Finding>) {
        val summary = loader.latest("external_frame_lifecycle").orEmpty()
        val wait = loader.latest("external_xrWaitFrame")
        val begin = loader.latest("external_xrBeginFrame")
        val end = loader.latest("external_xrEndFrame(projection)")
        val hasSuccess = listOf(wait, begin, end).any { isSuccess(it) }
        if (summary.contains("SKIPPED", ignoreCase = true) && hasSuccess) {
            findings += Finding(
                "FRAME_SUMMARY_SKIPPED_WITH_SUCCESSFUL_CALL", Severity.HIGH,
                "The frame lifecycle summary says SKIPPED while at least one frame call reports XR_SUCCESS.",
                listOf("external_frame_lifecycle", "external_xrWaitFrame", "external_xrBeginFrame", "external_xrEndFrame(projection)"),
                "Make the summary describe the same frame attempt as the individual call results."
            )
        }
    }

    private enum class Outcome { SUCCESS, FAILURE, SKIPPED, OTHER }

    private fun outcome(value: String): Outcome = when {
        value.startsWith("XR_SUCCESS") || value == "PASS" || value == "YES" -> Outcome.SUCCESS
        value.startsWith("XR_ERROR") || value == "FAIL" || value.startsWith("REJECTED") -> Outcome.FAILURE
        value.contains("SKIPPED", ignoreCase = true) || value == "NOT_RUN" -> Outcome.SKIPPED
        else -> Outcome.OTHER
    }

    private fun isSuccess(value: String?): Boolean = value != null && outcome(value) == Outcome.SUCCESS
    private fun isFailure(value: String?): Boolean = value != null && outcome(value) == Outcome.FAILURE

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
    private val ACTIVE_PACKAGE = Regex("active package=([A-Za-z0-9_.]+)")
    private const val MAX_REPORT_LENGTH = 256 * 1024
}
