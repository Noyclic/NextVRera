package com.noyclic.xrclientprobe

/**
 * A conservative, offline catalogue for OpenXR result codes.
 *
 * Numeric values are preserved as reported by the runtime. They are deliberately
 * not used as lookup keys because vendors occasionally wrap or decorate results
 * in log output. The symbolic XR_ERROR_* token is the stable diagnostic key.
 */
internal object ExternalOpenXrFailureCatalog {
    enum class Category {
        RUNTIME_DISCOVERY,
        INITIALIZATION,
        API_COMPATIBILITY,
        EXTENSION_SUPPORT,
        SYSTEM_SELECTION,
        GRAPHICS,
        SESSION_LIFECYCLE,
        SWAPCHAIN,
        FRAME_ORDER,
        RESOURCE_LIFECYCLE,
        INPUT,
        HAND_TRACKING,
        MEMORY,
        VALIDATION,
        UNKNOWN
    }

    enum class Impact { BLOCKING, HIGH, MEDIUM, LOW, UNKNOWN }

    data class Rule(
        val code: String,
        val category: Category,
        val impact: Impact,
        val summary: String,
        val likelyCause: String,
        val checks: List<String>,
        val actions: List<String>,
        val retryAdvice: String
    )

    data class Diagnosis(
        val rawResult: String,
        val code: String?,
        val numericValue: Int?,
        val category: Category,
        val impact: Impact,
        val summary: String,
        val likelyCause: String,
        val checks: List<String>,
        val actions: List<String>,
        val retryAdvice: String,
        val stageHint: ExternalOpenXrCompatibilityAssessment.StageId?,
        val known: Boolean
    ) {
        fun toDisplayText(): String = buildString {
            appendLine("OPENXR RESULT DIAGNOSIS")
            appendLine("result=" + rawResult)
            appendLine("code=" + (code ?: "UNCLASSIFIED"))
            numericValue?.let { appendLine("reported_numeric_value=" + it) }
            appendLine("category=" + category.name)
            appendLine("impact=" + impact.name)
            stageHint?.let { appendLine("stage_hint=" + it.name) }
            appendLine("summary=" + summary)
            appendLine("likely_cause=" + likelyCause)
            if (checks.isNotEmpty()) {
                appendLine("checks:")
                checks.forEach { appendLine("- " + it) }
            }
            if (actions.isNotEmpty()) {
                appendLine("actions:")
                actions.forEach { appendLine("- " + it) }
            }
            append("retry=" + retryAdvice)
        }
    }

    private val rules: Map<String, Rule> = listOf(
        Rule(
            "XR_ERROR_RUNTIME_UNAVAILABLE", Category.RUNTIME_DISCOVERY, Impact.BLOCKING,
            "The loader could not obtain a usable active OpenXR runtime.",
            "RuntimeBroker selection may be absent, the selected package may be unloadable, or the runtime provider may be invisible.",
            listOf("Compare installable and system RuntimeBroker provider results.", "Verify which broker the loader selected, not merely which provider exists.", "Check the selected package and ABI-specific native library path."),
            listOf("Resolve runtime selection before investigating later graphics or hand-tracking stages.", "Repeat the probe after confirming the selected package is EndXR."),
            "Do not retry in a tight loop; retry only after runtime selection or package visibility changes."
        ),
        Rule(
            "XR_ERROR_INITIALIZATION_FAILED", Category.INITIALIZATION, Impact.BLOCKING,
            "OpenXR runtime or loader initialization failed.",
            "The Android loader context, runtime negotiation, provider metadata, or runtime startup may be incomplete.",
            listOf("Inspect xrInitializeLoaderKHR and Android Activity lifetime.", "Check the RuntimeBroker response and service metadata.", "Look for the first earlier non-success result."),
            listOf("Fix the earliest failed initialization prerequisite.", "Capture a fresh report after process restart."),
            "A single retry after correcting setup is reasonable; repeated retries without a changed prerequisite are not useful."
        ),
        Rule(
            "XR_ERROR_API_VERSION_UNSUPPORTED", Category.API_COMPATIBILITY, Impact.BLOCKING,
            "The runtime rejected the requested OpenXR API version.",
            "The client requested a version outside the runtime's supported API range.",
            listOf("Compare requested and runtime-reported API versions.", "Check negotiated loader/runtime versions and extension requirements."),
            listOf("Request a version supported by both client and runtime.", "Do not infer full OpenXR 1.1 conformance from loader negotiation alone."),
            "Retry after changing the requested API version or runtime build."
        ),
        Rule(
            "XR_ERROR_EXTENSION_NOT_PRESENT", Category.EXTENSION_SUPPORT, Impact.HIGH,
            "A requested OpenXR extension is not available from the active runtime.",
            "The application requested an extension that the selected runtime did not enumerate.",
            listOf("Compare requested extensions with xrEnumerateInstanceExtensionProperties output.", "Identify the exact extension named by the client."),
            listOf("Remove the unsupported optional extension or provide a compatible implementation.", "Keep optional features disabled unless the runtime advertises them."),
            "Retry only after changing the requested extension set or active runtime."
        ),
        Rule(
            "XR_ERROR_FUNCTION_UNSUPPORTED", Category.EXTENSION_SUPPORT, Impact.HIGH,
            "The requested OpenXR function is not supported.",
            "The function may belong to an unavailable extension or an incomplete runtime implementation.",
            listOf("Check whether the owning extension was advertised and enabled.", "Check whether the function pointer was obtained from the correct instance or device."),
            listOf("Gate the call on extension enablement and a non-null function pointer.", "Treat optional function support separately from core runtime availability."),
            "Retry after changing extension/function selection."
        ),
        Rule(
            "XR_ERROR_FEATURE_UNSUPPORTED", Category.EXTENSION_SUPPORT, Impact.MEDIUM,
            "The runtime does not support the requested feature configuration.",
            "The feature may be optional, hardware-specific, or outside the runtime's implemented capability set.",
            listOf("Inspect the exact feature structure and extension that introduced it.", "Compare the requested feature with runtime capability properties."),
            listOf("Disable the optional feature or select a supported configuration."),
            "Retry with a reduced feature set."
        ),
        Rule(
            "XR_ERROR_FORM_FACTOR_UNSUPPORTED", Category.SYSTEM_SELECTION, Impact.BLOCKING,
            "The runtime does not support the requested HMD form factor.",
            "The runtime may be designed for a different device class or may not expose an HMD system.",
            listOf("Inspect the XrFormFactor passed to xrGetSystem.", "Check runtime product and system properties."),
            listOf("Use a form factor supported by the runtime.", "Do not treat Android phone orientation as proof of a head-mounted display implementation."),
            "Retry only with a supported form factor or runtime."
        ),
        Rule(
            "XR_ERROR_FORM_FACTOR_UNAVAILABLE", Category.SYSTEM_SELECTION, Impact.HIGH,
            "The requested HMD form factor is temporarily unavailable.",
            "The runtime may be busy, not ready, or unable to activate the requested system at this time.",
            listOf("Inspect runtime process state and any concurrent XR session.", "Check whether another client owns the device or session."),
            listOf("Stop competing XR clients and retry after the runtime returns to idle."),
            "A bounded retry after releasing competing sessions is reasonable."
        ),
        Rule(
            "XR_ERROR_SYSTEM_INVALID", Category.SYSTEM_SELECTION, Impact.BLOCKING,
            "The XrSystemId is invalid for this instance.",
            "The client may be using a stale system identifier or continuing after xrGetSystem failed.",
            listOf("Verify xrGetSystem returned XR_SUCCESS before using the identifier.", "Check that the identifier belongs to the current XrInstance."),
            listOf("Recreate the instance and obtain a fresh system identifier."),
            "Retry after rebuilding the instance/system sequence."
        ),
        Rule(
            "XR_ERROR_GRAPHICS_DEVICE_INVALID", Category.GRAPHICS, Impact.BLOCKING,
            "The graphics device supplied by the client is incompatible with the runtime.",
            "The EGL/GLES context may belong to an unsupported device, display, or graphics configuration.",
            listOf("Inspect the EGL display, context, config and GLES version.", "Compare the context with xrGetOpenGLESGraphicsRequirementsKHR.", "Verify the context is current on the thread creating the session."),
            listOf("Create the session with a context meeting the runtime graphics requirements.", "Keep graphics binding checks separate from successful instance creation."),
            "Retry after creating a new compatible graphics context."
        ),
        Rule(
            "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING", Category.GRAPHICS, Impact.HIGH,
            "Required graphics requirements were not queried before session creation.",
            "The client may have skipped or failed the graphics requirements call.",
            listOf("Check xrGetOpenGLESGraphicsRequirementsKHR result and ordering.", "Verify the extension was enabled and the function pointer resolved."),
            listOf("Query requirements before creating the GLES session.", "Validate the current GLES version against the returned range."),
            "Retry after correcting graphics initialization order."
        ),
        Rule(
            "XR_ERROR_SESSION_LOST", Category.SESSION_LIFECYCLE, Impact.BLOCKING,
            "The OpenXR session was lost.",
            "The runtime may have shut down the session, lost its device state, or rejected the current lifecycle.",
            listOf("Inspect session-state events before the failing call.", "Check whether the runtime process restarted or the Android Activity paused."),
            listOf("Stop issuing frame calls on the lost session.", "Destroy remaining valid handles and recreate the session only after runtime recovery."),
            "Do not retry frame calls on the same lost session."
        ),
        Rule(
            "XR_ERROR_SESSION_NOT_READY", Category.SESSION_LIFECYCLE, Impact.MEDIUM,
            "The session is not ready for the requested operation.",
            "The client may be submitting frames before the session reaches a running state.",
            listOf("Inspect xrPollEvent session-state transitions.", "Check whether READY and xrBeginSession were reached."),
            listOf("Wait for the appropriate session state before beginning the frame loop."),
            "Retry after the session reaches the required state."
        ),
        Rule(
            "XR_ERROR_SESSION_NOT_RUNNING", Category.SESSION_LIFECYCLE, Impact.MEDIUM,
            "The session is not running.",
            "The session may not have been begun or may already have stopped.",
            listOf("Inspect xrBeginSession/xrEndSession calls and session-state events.", "Verify the session handle is current."),
            listOf("Follow the session state machine before calling running-session functions."),
            "Retry only after a valid session start."
        ),
        Rule(
            "XR_ERROR_INSTANCE_LOST", Category.RESOURCE_LIFECYCLE, Impact.BLOCKING,
            "The OpenXR instance was lost.",
            "The runtime or loader invalidated the instance; child handles must not be reused.",
            listOf("Check for runtime process death or service restart.", "Inspect the earliest call that began returning errors."),
            listOf("Discard all handles owned by the lost instance.", "Create a new instance after runtime recovery."),
            "Do not retry with handles from the lost instance."
        ),
        Rule(
            "XR_ERROR_CALL_ORDER_INVALID", Category.FRAME_ORDER, Impact.HIGH,
            "OpenXR calls were issued in an invalid lifecycle order.",
            "A required begin/wait/end, acquire/wait/release, or create/destroy prerequisite may have been skipped or repeated.",
            listOf("Compare the call sequence with the OpenXR lifecycle for the failing function.", "Check exactly-once pairing for xrBeginFrame/xrEndFrame and swapchain image acquire/release."),
            listOf("Repair the earliest ordering violation, then rerun the probe from a fresh instance."),
            "Retry after correcting call order; retrying the same sequence will not help."
        ),
        Rule(
            "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED", Category.SWAPCHAIN, Impact.HIGH,
            "The requested swapchain image format is unsupported.",
            "The client selected a format not returned by xrEnumerateSwapchainFormats.",
            listOf("Record the formats enumerated by the runtime.", "Compare the exact requested integer format with the returned list."),
            listOf("Select a format advertised by the active runtime and supported by the graphics API."),
            "Retry after selecting a supported format."
        ),
        Rule(
            "XR_ERROR_SWAPCHAIN_RECT_INVALID", Category.SWAPCHAIN, Impact.HIGH,
            "The submitted swapchain rectangle is invalid.",
            "The rectangle may exceed the image bounds, contain a zero extent, or use an invalid offset.",
            listOf("Check offset and extent for each eye.", "Compare the rectangle against the acquired swapchain image dimensions."),
            listOf("Clamp the rectangle to image bounds and use a non-zero valid extent."),
            "Retry after correcting the submitted rectangle."
        ),
        Rule(
            "XR_ERROR_LAYER_INVALID", Category.FRAME_ORDER, Impact.HIGH,
            "The submitted composition layer is invalid.",
            "The layer may contain invalid views, swapchain references, rectangles, poses, or a stale pointer.",
            listOf("Check view count and per-eye view structures.", "Verify each referenced swapchain image was released before xrEndFrame.", "Check structure types and next pointers."),
            listOf("Build a minimal valid projection layer and add optional fields incrementally."),
            "Retry with a corrected layer; do not loop on the same invalid submission."
        ),
        Rule(
            "XR_ERROR_TIME_INVALID", Category.FRAME_ORDER, Impact.MEDIUM,
            "The supplied XrTime value is invalid.",
            "The timestamp may be zero, stale, from a different clock domain, or not predicted for the current frame.",
            listOf("Use predictedDisplayTime from the current xrWaitFrame result where required.", "Do not mix wall-clock and monotonic time values."),
            listOf("Propagate the runtime-provided XrTime without changing clock domains."),
            "Retry with a timestamp obtained from the current frame sequence."
        ),
        Rule(
            "XR_ERROR_HANDLE_INVALID", Category.RESOURCE_LIFECYCLE, Impact.HIGH,
            "An OpenXR handle is invalid.",
            "The handle may be uninitialized, already destroyed, or owned by another instance/session.",
            listOf("Trace creation and destruction of the exact handle type.", "Check for stale handles after session or instance loss."),
            listOf("Use only handles created by the current parent object and destroy each exactly once."),
            "Retry only after recreating the invalid handle's parent chain."
        ),
        Rule(
            "XR_ERROR_VALIDATION_FAILURE", Category.VALIDATION, Impact.HIGH,
            "The runtime rejected the call because its parameters failed validation.",
            "One or more fields, flags, structure types, array counts, or ownership relationships may be invalid.",
            listOf("Inspect all fields of the failing structure, including type and next.", "Check required flags, array counts, ranges and handle ownership."),
            listOf("Reduce the call to a minimal valid structure, then add optional data incrementally."),
            "Retry after correcting the invalid parameter set."
        ),
        Rule(
            "XR_ERROR_RUNTIME_FAILURE", Category.INITIALIZATION, Impact.HIGH,
            "The runtime reported an unspecified runtime failure.",
            "The underlying runtime may have encountered an internal failure or vendor-specific error.",
            listOf("Inspect adjacent log lines and the last verified stage.", "Check whether the runtime process or Android service crashed."),
            listOf("Capture a fresh report and preserve the original result text for vendor diagnosis."),
            "Use at most one bounded retry after runtime recovery."
        ),
        Rule(
            "XR_ERROR_OUT_OF_MEMORY", Category.MEMORY, Impact.HIGH,
            "The runtime or client could not allocate required memory.",
            "Swapchain size, image count, texture format, or concurrent camera/GPU allocations may exceed available resources.",
            listOf("Record available memory and thermal state.", "Inspect swapchain dimensions, image count and other large allocations."),
            listOf("Reduce render resolution and swapchain image count within runtime requirements.", "Release temporary buffers and avoid duplicate allocations."),
            "Retry only after releasing memory or reducing the allocation footprint."
        ),
        Rule(
            "XR_ERROR_LIMIT_REACHED", Category.MEMORY, Impact.MEDIUM,
            "A runtime-defined resource or object limit was reached.",
            "The client may have created too many paths, actions, swapchains, spaces, or other objects.",
            listOf("Inspect the object type and creation count at the failing call.", "Check for handles that were not destroyed after a previous run."),
            listOf("Reuse objects where permitted and destroy unused handles."),
            "Retry after reducing the object count."
        ),
        Rule(
            "XR_ERROR_SIZE_INSUFFICIENT", Category.VALIDATION, Impact.MEDIUM,
            "The supplied output buffer or capacity is too small.",
            "The client may have used a stale count or failed to follow the two-call enumeration pattern.",
            listOf("Check the count returned by the first enumeration call.", "Reallocate using the reported capacity and retry the list call."),
            listOf("Use the OpenXR count-then-fill pattern and handle changing counts safely."),
            "A bounded repeat of the enumeration call is appropriate."
        ),
        Rule(
            "XR_ERROR_PATH_INVALID", Category.INPUT, Impact.MEDIUM,
            "An OpenXR path is invalid.",
            "The path string may not have been converted correctly or may not be valid for this instance.",
            listOf("Check xrStringToPath input and exact path spelling.", "Verify the instance is still valid."),
            listOf("Use a valid OpenXR path and retain the returned XrPath for this instance."),
            "Retry after correcting the path."
        ),
        Rule(
            "XR_ERROR_PATH_UNSUPPORTED", Category.INPUT, Impact.LOW,
            "The runtime does not support the requested interaction path.",
            "A device-specific input path may not exist on this runtime.",
            listOf("Enumerate interaction profiles and supported paths.", "Check whether the path belongs to an optional controller or hand profile."),
            listOf("Select a supported path or provide a safe optional-input fallback."),
            "Retry with a supported interaction profile."
        ),
        Rule(
            "XR_ERROR_ACTION_TYPE_MISMATCH", Category.INPUT, Impact.MEDIUM,
            "The action type does not match the requested operation.",
            "A Boolean, Float, Vector2, Pose, or Vibration action may be used through the wrong API.",
            listOf("Compare the action creation type with the getter/setter used.", "Check that action bindings and subaction paths are compatible."),
            listOf("Use the getter/setter matching the declared action type."),
            "Retry after recreating the action with a consistent type."
        ),
        Rule(
            "XR_ERROR_REFERENCE_SPACE_UNSUPPORTED", Category.SESSION_LIFECYCLE, Impact.MEDIUM,
            "The runtime does not support the requested reference space.",
            "The runtime may expose only a limited set of spaces or may lack calibrated room-scale tracking.",
            listOf("Enumerate reference spaces supported by the current session.", "Distinguish LOCAL, STAGE and LOCAL_FLOOR support."),
            listOf("Choose a supported reference space and do not claim calibrated floor or room bounds unless measured."),
            "Retry with a supported reference space."
        ),
        Rule(
            "XR_ERROR_POSE_INVALID", Category.INPUT, Impact.LOW,
            "The supplied pose is invalid.",
            "The pose may contain a non-normalized quaternion or invalid position/orientation values.",
            listOf("Check quaternion normalization and finite numeric values.", "Verify the pose belongs to the selected reference space."),
            listOf("Normalize valid rotations and reject NaN or infinite pose components."),
            "Retry after supplying a finite valid pose."
        )
    ).associateBy { it.code }

    private val resultPattern = Regex("XR_ERROR_[A-Z0-9_]+")
    private val numericPattern = Regex("""\((-?\d+)\)""")

    fun knownCodes(): Set<String> = rules.keys

    fun diagnose(
        rawResult: String?,
        key: String? = null,
        stage: ExternalOpenXrCompatibilityAssessment.StageId? = null
    ): Diagnosis? {
        val raw = rawResult?.trim()?.take(MAX_RESULT_LENGTH) ?: return null
        if (raw.isEmpty()) return null
        val code = resultPattern.find(raw)?.value
        val numericValue = numericPattern.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val resolvedStage = stage ?: stageForKey(key)
        val rule = code?.let { rules[it] }
        if (rule != null) {
            val context = contextualChecks(key, resolvedStage)
            return Diagnosis(
                rawResult = raw,
                code = code,
                numericValue = numericValue,
                category = rule.category,
                impact = rule.impact,
                summary = rule.summary,
                likelyCause = rule.likelyCause,
                checks = (rule.checks + context).distinct(),
                actions = contextualActions(key, resolvedStage, rule.actions).distinct(),
                retryAdvice = rule.retryAdvice,
                stageHint = resolvedStage,
                known = true
            )
        }
        if (code != null) {
            return Diagnosis(
                rawResult = raw,
                code = code,
                numericValue = numericValue,
                category = categoryForStage(resolvedStage),
                impact = Impact.UNKNOWN,
                summary = "The runtime returned an OpenXR error that is not in the local diagnostic catalogue.",
                likelyCause = "The result may be vendor-specific or introduced by a newer OpenXR specification revision.",
                checks = listOf("Preserve the exact symbolic and numeric result.", "Check the OpenXR specification version used by the runtime and client."),
                actions = listOf("Inspect the call parameters and the preceding successful stage.", "Do not replace an unknown vendor result with a guessed error meaning."),
                retryAdvice = "Retry only after changing a prerequisite or recovering the runtime.",
                stageHint = resolvedStage,
                known = false
            )
        }
        if (raw.contains("FAIL", ignoreCase = true) || raw.contains("DENIED", ignoreCase = true) ||
            raw.contains("REJECTED", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true)
        ) {
            return Diagnosis(
                rawResult = raw,
                code = null,
                numericValue = numericValue,
                category = categoryForStage(resolvedStage),
                impact = if (raw.contains("DENIED", true)) Impact.HIGH else Impact.UNKNOWN,
                summary = "A non-OpenXR diagnostic reported a failure-like state.",
                likelyCause = "The text may describe an Android provider, EGL, IPC, or probe-specific failure rather than an OpenXR result code.",
                checks = contextualChecks(key, resolvedStage) + "Find the component that emitted this diagnostic line.",
                actions = listOf("Keep this failure separate from OpenXR result-code failures.", "Inspect the exact raw diagnostic field."),
                retryAdvice = "Retry only after the underlying provider or probe condition changes.",
                stageHint = resolvedStage,
                known = false
            )
        }
        return null
    }

    private fun contextualChecks(
        key: String?,
        stage: ExternalOpenXrCompatibilityAssessment.StageId?
    ): List<String> = buildList {
        when {
            key?.contains("egl", ignoreCase = true) == true || stage == ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING ->
                add("For this graphics call, record EGL display/context identity and current-thread ownership.")
            key?.contains("Swapchain", ignoreCase = true) == true || stage == ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN ->
                add("For this swapchain call, record format, dimensions, sample count and image count.")
            key?.contains("Frame", ignoreCase = true) == true || stage == ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP ->
                add("For this frame call, record the preceding wait/begin/acquire/wait/release sequence.")
            key?.contains("Hand", ignoreCase = true) == true || stage == ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING ->
                add("For this hand-tracking call, record extension enablement and both left/right tracker results.")
            key?.contains("Broker", ignoreCase = true) == true || stage == ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER ->
                add("For this broker result, distinguish provider visibility, query completion and selected package.")
            key?.contains("input", ignoreCase = true) == true || stage == ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE ->
                add("For this input result, distinguish package visibility, permission grant and provider query success.")
        }
    }

    private fun contextualActions(
        key: String?,
        stage: ExternalOpenXrCompatibilityAssessment.StageId?,
        base: List<String>
    ): List<String> = when {
        stage == ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER ->
            listOf("Check which RuntimeBroker provider the Khronos loader selected and whether its active package is EndXR.") +
                base + "Do not spend time debugging swapchain or hand tracking until the runtime is selected."
        stage == ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING ->
            base + "Do not reuse an EGL context after its owning surface or Activity has been destroyed."
        stage == ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP ||
            stage == ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION ->
            base + "Stop the frame loop after the first lifecycle error and perform bounded cleanup."
        key?.contains("Hand", ignoreCase = true) == true ->
            base + "Keep hand-tracking failure independent from the basic stereo rendering verdict."
        else -> base
    }

    private fun stageForKey(key: String?): ExternalOpenXrCompatibilityAssessment.StageId? {
        if (key == null) return null
        return when {
            key.contains("Broker", ignoreCase = true) -> ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER
            key.contains("InitializeLoader") -> ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION
            key.contains("Extension") -> ExternalOpenXrCompatibilityAssessment.StageId.EXTENSION_ENUMERATION
            key.contains("CreateInstance") -> ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION
            key.contains("GetSystem") -> ExternalOpenXrCompatibilityAssessment.StageId.HMD_SYSTEM
            key.contains("ViewConfiguration") -> ExternalOpenXrCompatibilityAssessment.StageId.STEREO_CONFIGURATION
            key.contains("egl", ignoreCase = true) || key.contains("GraphicsRequirements") ->
                ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING
            key.contains("CreateSession") -> ExternalOpenXrCompatibilityAssessment.StageId.SESSION_CREATION
            key.contains("Swapchain") -> ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN
            key.contains("EndFrame") -> ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION
            key.contains("Frame") || key.contains("SwapchainImage") ->
                ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP
            key.contains("Hand") -> ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING
            key.contains("input", ignoreCase = true) -> ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE
            key.contains("Destroy") -> ExternalOpenXrCompatibilityAssessment.StageId.CLEANUP
            else -> null
        }
    }

    private fun categoryForStage(stage: ExternalOpenXrCompatibilityAssessment.StageId?): Category =
        when (stage) {
            ExternalOpenXrCompatibilityAssessment.StageId.RUNTIME_BROKER -> Category.RUNTIME_DISCOVERY
            ExternalOpenXrCompatibilityAssessment.StageId.LOADER_INITIALIZATION,
            ExternalOpenXrCompatibilityAssessment.StageId.INSTANCE_CREATION -> Category.INITIALIZATION
            ExternalOpenXrCompatibilityAssessment.StageId.EXTENSION_ENUMERATION -> Category.EXTENSION_SUPPORT
            ExternalOpenXrCompatibilityAssessment.StageId.HMD_SYSTEM,
            ExternalOpenXrCompatibilityAssessment.StageId.STEREO_CONFIGURATION -> Category.SYSTEM_SELECTION
            ExternalOpenXrCompatibilityAssessment.StageId.GRAPHICS_BINDING -> Category.GRAPHICS
            ExternalOpenXrCompatibilityAssessment.StageId.SESSION_CREATION -> Category.SESSION_LIFECYCLE
            ExternalOpenXrCompatibilityAssessment.StageId.SWAPCHAIN -> Category.SWAPCHAIN
            ExternalOpenXrCompatibilityAssessment.StageId.FRAME_LOOP,
            ExternalOpenXrCompatibilityAssessment.StageId.PROJECTION_SUBMISSION -> Category.FRAME_ORDER
            ExternalOpenXrCompatibilityAssessment.StageId.HAND_TRACKING -> Category.HAND_TRACKING
            ExternalOpenXrCompatibilityAssessment.StageId.INPUT_BRIDGE -> Category.INPUT
            ExternalOpenXrCompatibilityAssessment.StageId.CLEANUP -> Category.RESOURCE_LIFECYCLE
            else -> Category.UNKNOWN
        }


    private const val MAX_RESULT_LENGTH = 240
}
