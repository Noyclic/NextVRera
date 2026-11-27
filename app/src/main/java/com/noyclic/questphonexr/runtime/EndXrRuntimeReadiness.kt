package com.noyclic.questphonexr.runtime

enum class RuntimeCapabilityState(val label: String) {
    VERIFIED_IN_PROCESS("Проверено внутри QuestPhoneXR"),
    PARTIAL("Частично реализовано"),
    NOT_IMPLEMENTED("Пока не реализовано"),
    NOT_VERIFIED("Ещё не проверено")
}

data class RuntimeCapability(
    val id: String,
    val title: String,
    val state: RuntimeCapabilityState,
    val details: String
)

data class RuntimeOpenXrExtensionCapability(
    val name: String,
    val state: RuntimeCapabilityState,
    val details: String
)

/**
 * Conservative capability inventory for the bundled EndXR implementation.
 * In-process verification is not a claim of third-party process or headset compatibility.
 */
object EndXrRuntimeReadiness {
    /**
     * Mirrors the extension names exposed by endxr_openxr_runtime.cpp.
     * VERIFIED_IN_PROCESS covers bundled tests and Sandbox only; it does not
     * claim RuntimeBroker selection or third-party game compatibility.
     */
    val openXrExtensions: List<RuntimeOpenXrExtensionCapability> = listOf(
        RuntimeOpenXrExtensionCapability(
            "XR_KHR_android_create_instance",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "Android instance creation is advertised and exercised by bundled runtime tests. External Loader/RuntimeBroker selection by a separate game process is not yet verified."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_KHR_opengl_es_enable",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "The OpenGL ES binding, session setup and swapchain path are exercised in the bundled Sandbox. Third-party game compatibility remains a separate test."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_EXT_hand_tracking",
            RuntimeCapabilityState.PARTIAL,
            "26 camera-estimated joints are exposed, but several joints are inferred, poses are not marked TRACKED, and external-game behavior is not yet verified."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_EXT_hand_interaction",
            RuntimeCapabilityState.PARTIAL,
            "Pinch, grasp and aim-activate interaction values are exposed through the current input path; physical 6DoF controllers, real controller buttons/sticks and haptics are not implemented."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_KHR_android_thread_settings",
            RuntimeCapabilityState.PARTIAL,
            "The runtime makes a best-effort Android thread-priority request. The OS may reject priority changes; actual scheduling effects are not verified on the target phone."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_KHR_convert_timespec_time",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "Conversion between OpenXR XrTime and POSIX struct timespec monotonic timestamps is implemented and covered by native tests."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_KHR_locate_spaces",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "Batched multi-space pose queries via xrLocateSpacesKHR (and OpenXR 1.1 core xrLocateSpaces) are implemented and covered by native tests."
        ),
        RuntimeOpenXrExtensionCapability(
            "XR_EXT_local_floor",
            RuntimeCapabilityState.PARTIAL,
            "LOCAL_FLOOR and STAGE use a fixed 1.65 m eye-height estimate. This is not calibrated floor tracking, does not provide room bounds, and does not add positional 6DoF."
        )
    )

    val capabilities: List<RuntimeCapability> = listOf(
        RuntimeCapability("loader-negotiation", "OpenXR loader negotiation",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "Bundled runtime negotiation and its loader-facing entrypoint table are covered by native tests."),
        RuntimeCapability("android-gles-session", "Android + OpenGL ES session",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "OpenXR GLES session setup, stereo view configuration and swapchain lifecycle are exercised in Sandbox."),
        RuntimeCapability("stereo-projection", "Stereo projection layer",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "The Sandbox probe submits two eye views and verifies compositor output on its own EGL surface."),
        RuntimeCapability("head-orientation", "Head orientation",
            RuntimeCapabilityState.PARTIAL,
            "The phone rotation-vector sensor publishes a normalized quaternion through the protected Android IPC provider. QuestPhoneXR can grant one selected installed launcher app read access to the exact input URI; this is orientation-only, not positional 6DoF."),
        RuntimeCapability("position-tracking", "6DoF position tracking",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "The current OpenXR system properties explicitly advertise positionTracking=false."),
        RuntimeCapability("openxr-actions", "OpenXR Action / Input API",
            RuntimeCapabilityState.PARTIAL,
            "Action sets, action lifecycle, paths, binding suggestions, action spaces and xrSyncActions are implemented. Each sync captures one immutable left/right controller snapshot; Boolean/Float/Vector2 values and changedSinceLastSync are resolved from that snapshot. An unrelated game process needs an explicit package-scoped grant for the controller_state URI."),
        RuntimeCapability("controller-input", "Controller input and cross-process transport",
            RuntimeCapabilityState.PARTIAL,
            "A user-started camera foreground service publishes per-hand pinch trigger, continuous 0..1 finger-closure grasp strength (the three-or-more-finger power grip classifier remains available to Sandbox), approximate palm poses and 26-joint hand samples through one protected read-only snapshot. QuestPhoneXR can grant the exact controller_state URI to one selected installed launcher app; approval is bound to that package's signing-certificate fingerprint and can be revoked. This camera path does not inject Android gamepad events or fabricate stick axes and physical face buttons, and real-game input remains unverified. POSE_INPUT / xrLocateSpace expose estimates with POSITION_VALID and ORIENTATION_VALID but never TRACKED flags; true 6DoF controllers and haptics remain unavailable."),
        RuntimeCapability("input-package-authorization", "Package-scoped input authorization",
            RuntimeCapabilityState.PARTIAL,
            "The UI lists installed third-party launcher apps and asks for confirmation before granting read-only access to only /controller_state. The stored approval is tied to the package signing-certificate fingerprint; the temporary URI grant is re-issued on the next QuestPhoneXR launch only while the same package and signer remain installed. The user can revoke access. The receiving game still needs to implement the EndXR input contract."),
        RuntimeCapability("openxr-hand-tracking", "OpenXR hand-tracking extension",
            RuntimeCapabilityState.PARTIAL,
            "The native runtime advertises XR_EXT_hand_tracking (26 camera-estimated joints) and XR_EXT_hand_interaction (separate pinch/grasp/aim-activate values, readiness states, and grip/pinch/poke/aim action poses). Aim activation is estimated from an extended index finger with the other long fingers curled; controller-only input falls back to trigger. MediaPipe's 21 landmarks are mapped to the OpenXR layout; several base joints are estimated. Hand-derived positions/orientations are marked POSITION_VALID / ORIENTATION_VALID but never TRACKED. External-game input and physical-device compatibility have not yet been verified."),
        RuntimeCapability("runtime-broker", "Runtime discovery from a separate diagnostic client",
            RuntimeCapabilityState.NOT_VERIFIED,
            "The separately signed diagnostic APK verifies service visibility, signature-protected input IPC and official Khronos loader initialization in CI. Active RuntimeBroker selection and successful xrCreateInstance on the Redmi remain unverified.")
    )

    val librarySummary: String =
        "Внутри Sandbox проверены loader negotiation, Android/GLES session и stereo projection layer. " +
            "OpenXR Actions API частично реализован; camera foreground service передаёт pinch и аналоговую силу grasp (0..1), приближённые palm poses и 26 суставов каждой руки через защищённый IPC. XR_EXT_hand_tracking и XR_EXT_hand_interaction реализованы частично: pinch, analog grasp и отдельный aim-activate жест доступны через OpenXR Actions, но внешний игровой процесс ещё не проверен. " +
            "Для отдельной игры можно вручную разрешить чтение только URI controller_state одному установленному приложению; разрешение привязано к подписи пакета и отзывается из QuestPhoneXR. " +
            "Это не означает, что любая игра уже поддерживает контракт ввода. Пози ладони доступны через POSE_INPUT/xrLocateSpace без TRACKED-флагов; Android gamepad events, настоящее 6DoF и haptics пока отсутствуют. " +
            "Выбор runtime через RuntimeBroker ещё не подтверждён на физическом телефоне."

    const val OPEN_XR_GAME_WARNING =
        "Перед запуском внешней игры учитывай ограничения: camera foreground service передаёт MediaPipe pinch и аналоговую силу grasp (0..1), приближённую позу ладони и 26 суставов через IPC; XR_EXT_hand_tracking и XR_EXT_hand_interaction доступны частично (pinch, grasp, aim-activate и hand poses), но внешний игровой процесс ещё не проверен. " +
            "В QuestPhoneXR можно вручную выбрать одно установленное приложение и выдать ему read-only доступ только к URI controller_state; разрешение привязано к подписи пакета и может быть отозвано. " +
            "Игра всё равно должна поддерживать этот IPC-контракт. Это не настоящие tracked controller poses и не 6DoF; Android gamepad capture и haptics пока отсутствуют. STAGE/LOCAL_FLOOR используют фиксированную оценку высоты глаз 1,65 м, а не калиброванный пол или guardian bounds."

    const val QUEST_GAME_WARNING =
        "Quest APK может зависеть от Meta/Oculus Runtime и системных сервисов гарнитуры. На обычном Android " +
            "их нет; нужен отдельный телефонный порт либо совместимая реализация этих API."

    fun warningsFor(xrTechnologies: Collection<String>): List<String> = buildList {
        if (xrTechnologies.any { it.equals("OpenXR", ignoreCase = true) }) add(OPEN_XR_GAME_WARNING)
        if (xrTechnologies.any { it.contains("Meta/Oculus", ignoreCase = true) }) add(QUEST_GAME_WARNING)
    }
}


internal data class EndXrExternalRuntimeReadinessEvidence(
    val ownServiceFound: Boolean,
    val ownServiceExported: Boolean,
    val majorVersion: Int?,
    val expectedMajorVersion: Int,
    val soFilenamePresent: Boolean,
    val nativeLibraryExists: Boolean,
    val brokerProviderVisible: Boolean,
    val brokerQueryCompleted: Boolean,
    val brokerSelectedRuntimePackage: String?,
    val brokerSelectedRuntimeLibraryExists: Boolean,
    val ownPackageName: String,
    val openXrProbePass: Boolean,
    val openXrProbeFail: Boolean,
) {
    val metadataReady: Boolean
        get() = ownServiceFound && ownServiceExported &&
            majorVersion == expectedMajorVersion && soFilenamePresent && nativeLibraryExists
    val brokerSelectionReady: Boolean
        get() = brokerProviderVisible && brokerQueryCompleted &&
            brokerSelectedRuntimePackage == ownPackageName && brokerSelectedRuntimeLibraryExists
}

internal enum class EndXrExternalRuntimeReadinessVerdict { READY, DEGRADED, BLOCKED }

internal data class EndXrExternalRuntimeReadiness(
    val verdict: EndXrExternalRuntimeReadinessVerdict,
    val metadataReady: Boolean,
    val brokerSelectionReady: Boolean,
    val nativeProbeReady: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
) {
    fun toDisplayText(): String = buildString {
        appendLine("EndXR external runtime readiness: ${verdict.name}")
        appendLine("metadata_ready=$metadataReady")
        appendLine("broker_selection_ready=$brokerSelectionReady")
        appendLine("native_openxr_probe_ready=$nativeProbeReady")
        blockers.forEach { appendLine("blocker=$it") }
        warnings.forEach { appendLine("warning=$it") }
        append("This is a readiness assessment only; it does not prove that an arbitrary Quest APK will launch or render correctly.")
    }

    companion object {
        fun evaluate(e: EndXrExternalRuntimeReadinessEvidence): EndXrExternalRuntimeReadiness {
            val blockers = buildList {
                if (!e.ownServiceFound) add("runtime service marker is not visible to PackageManager")
                if (!e.ownServiceExported) add("runtime service is not exported")
                if (e.majorVersion != e.expectedMajorVersion) add("OpenXR major-version metadata does not match the loader contract")
                if (!e.soFilenamePresent) add("SoFilename metadata is missing")
                if (!e.nativeLibraryExists) add("runtime native library is missing")
                if (e.brokerProviderVisible && e.brokerQueryCompleted &&
                    e.brokerSelectedRuntimePackage != null &&
                    e.brokerSelectedRuntimePackage != e.ownPackageName
                ) add("RuntimeBroker selected a different runtime package")
            }
            val warnings = buildList {
                if (!e.brokerProviderVisible) add("no visible RuntimeBroker provider; external loader selection is unverified")
                else if (!e.brokerQueryCompleted) add("RuntimeBroker query did not complete")
                else if (!e.brokerSelectionReady) add("RuntimeBroker did not expose a loadable EndXR candidate")
                if (!e.openXrProbePass && !e.openXrProbeFail) add("native OpenXR GLES self-test has not run on the current surface")
                else if (e.openXrProbeFail) add("native OpenXR GLES self-test failed")
            }
            val verdict = when {
                blockers.isNotEmpty() -> EndXrExternalRuntimeReadinessVerdict.BLOCKED
                e.metadataReady && e.brokerSelectionReady && e.openXrProbePass -> EndXrExternalRuntimeReadinessVerdict.READY
                else -> EndXrExternalRuntimeReadinessVerdict.DEGRADED
            }
            return EndXrExternalRuntimeReadiness(verdict, e.metadataReady, e.brokerSelectionReady, e.openXrProbePass, blockers, warnings)
        }

        fun fromBrokerProbe(
            report: RuntimeBrokerProbeReport,
            nativeProbeLabel: String,
            ownPackageName: String,
            expectedMajorVersion: Int = 1,
        ): EndXrExternalRuntimeReadiness {
            val brokerVisible = report.installableBrokerStatus.contains("active runtime query=") ||
                report.systemBrokerStatus.contains("active runtime query=")
            val queryCompleted = brokerVisible &&
                !report.installableBrokerStatus.contains("query failed:") &&
                !report.systemBrokerStatus.contains("query failed:")
            return evaluate(EndXrExternalRuntimeReadinessEvidence(
                ownServiceFound = report.ownRuntimeServiceFound,
                ownServiceExported = report.ownRuntimeServiceExported,
                majorVersion = report.majorVersion,
                expectedMajorVersion = expectedMajorVersion,
                soFilenamePresent = !report.soFilename.isNullOrBlank(),
                nativeLibraryExists = report.nativeLibraryExists,
                brokerProviderVisible = brokerVisible,
                brokerQueryCompleted = queryCompleted,
                brokerSelectedRuntimePackage = report.loaderSelectedRuntimePackage,
                brokerSelectedRuntimeLibraryExists = report.loaderSelectedRuntimeLibraryExists,
                ownPackageName = ownPackageName,
                openXrProbePass = nativeProbeLabel.startsWith("OpenXR probe: PASS"),
                openXrProbeFail = nativeProbeLabel.startsWith("OpenXR probe: FAIL"),
            ))
        }
    }
}
