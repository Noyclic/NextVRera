package com.noyclic.questphonexr.catalog

import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import com.noyclic.questphonexr.gvr.EndGvrClassification
import com.noyclic.questphonexr.gvr.EndGvrCompatibility
import com.noyclic.questphonexr.runtime.EndXrRuntimeReadiness
import com.noyclic.questphonexr.runtime.RuntimeCapabilityState

internal enum class ImportedApkInstallMatch {
    NOT_INSTALLED,
    MATCHES_IMPORTED_VERSION,
    DIFFERENT_VERSION
}

internal fun classifyImportedApkInstall(
    importedVersionCode: Long,
    installedVersionCode: Long?
): ImportedApkInstallMatch = when {
    installedVersionCode == null -> ImportedApkInstallMatch.NOT_INSTALLED
    installedVersionCode == importedVersionCode -> ImportedApkInstallMatch.MATCHES_IMPORTED_VERSION
    else -> ImportedApkInstallMatch.DIFFERENT_VERSION
}

enum class ApkLaunchRoute(val label: String) {
    DEVICE_REQUIREMENTS_BLOCKED("Телефон не соответствует требованиям APK"),
    QUEST_RUNTIME_REQUIRED("Требуется Meta Quest Runtime — напрямую не запускается"),
    GEAR_VR_LEGACY_CANDIDATE("Обнаружены Gear VR признаки — нужна проверка через EndGVR"),
    LEGACY_OCULUS_MOBILE_CANDIDATE("Обнаружен старый Oculus Mobile SDK — нужна проверка legacy API"),
    OPENXR_RUNTIME_CANDIDATE("Обнаружен OpenXR — нужен активный совместимый Runtime"),
    XR_SOURCE_ADAPTATION_REQUIRED("Нужен XR-порт или совместимый адаптер"),
    ANDROID_APP_CANDIDATE("Кандидат на обычный Android-запуск")
}

data class ApkStaticAudit(
    val nativeLibraries: List<String> = emptyList(),
    val sdkRuntimeMarkers: List<String> = emptyList(),
    val manifestReferences: List<String> = emptyList(),
    val metadataValues: List<String> = emptyList(),
    val componentReferences: List<String> = emptyList(),
    val vendorRuntimeReferences: List<String> = emptyList(),
    val openXrExtensionReferences: List<String> = emptyList(),
    val openXrEntryPointReferences: List<String> = emptyList(),
    val graphicsApiMarkers: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val requiredFeatures: List<String> = emptyList(),
    val declaredActivities: List<String> = emptyList(),
    val declaredServices: List<String> = emptyList(),
    val declaredProviders: List<String> = emptyList(),
    val abiSummary: String = "ABI не проверен."
)

enum class ApkCompatibilityFindingSeverity(val label: String) {
    BLOCKER("БЛОКЕР"),
    WARNING("ПРЕДУПРЕЖДЕНИЕ"),
    INFO("ИНФОРМАЦИЯ")
}

data class ApkCompatibilityFinding(
    val id: String,
    val severity: ApkCompatibilityFindingSeverity,
    val title: String,
    val evidence: String,
    val impact: String,
    val nextStep: String
)

data class ApkRuntimeAssessment(
    val route: ApkLaunchRoute,
    val engine: String,
    val xrTechnologies: List<String>,
    val details: List<String>,
    val audit: ApkStaticAudit = ApkStaticAudit(),
    val findings: List<ApkCompatibilityFinding> = emptyList()
)

/**
 * Conservative APK triage for a phone-hosted XR runtime.
 *
 * This class inspects archive filenames, manifest metadata and bounded string references
 * in packaged native libraries. It never executes APK code and never labels an APK as
 * VR-compatible based on heuristics. Native strings are hints, not proof of required APIs.
 */
object ApkRuntimeCompatibility {
    private data class ArchiveInspection(
        val entries: List<String>,
        val nativeStringReferences: List<String>
    )

    // APK native-library scans are expensive. Keep a small process-wide LRU so
    // returning to the library never decompresses the same archive on every resume.
    private val archiveInspectionCache = object : LinkedHashMap<String, ArchiveInspection>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchiveInspection>?): Boolean =
            size > MAX_CACHED_ARCHIVES
    }

    private const val MAX_CACHED_ARCHIVES = 8
    private const val MAX_NATIVE_SCAN_BYTES_PER_LIBRARY = 16L * 1024L * 1024L
    private const val MAX_TOTAL_NATIVE_SCAN_BYTES = 64L * 1024L * 1024L
    private const val MAX_STRING_CARRY = 192
    private const val MAX_EXTENSION_REFERENCES = 48
    private val XR_EXTENSION_PATTERN = Regex("""XR_[A-Z0-9]+_[A-Za-z0-9_]+""")
    private val GRAPHICS_API_MARKERS = listOf(
        "libvulkan.so",
        "vkCreateInstance",
        "vkGetInstanceProcAddr",
        "vkQueueSubmit",
        "vkCreateSwapchainKHR",
        "eglCreateContext",
        "eglSwapBuffers",
        "glCreateShader"
    )
    private val OPENXR_ENTRY_POINT_MARKERS = listOf(
        "xrInitializeLoaderKHR",
        "xrCreateInstance",
        "xrGetSystem",
        "xrCreateSession",
        "xrCreateSwapchain",
        "xrBeginFrame",
        "xrEndFrame",
        "xrLocateViews",
        "xrSyncActions",
        "xrGetActionStateBoolean",
        "xrCreateHandTrackerEXT",
        "xrLocateHandJointsEXT"
    )

    fun inspect(
        info: ImportedApkInfo,
        archive: File,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>
    ): ApkRuntimeAssessment {
        val archiveInspection = cachedArchiveInspection(archive) ?: inspectArchive(archive).also {
            rememberArchiveInspection(archive, it)
        }
        return assessmentFrom(
            info,
            archiveInspection,
            deviceApiLevel,
            supportedAbis,
            missingRequiredFeatures
        )
    }

    /** Returns a cached assessment without doing ZIP or native-library I/O. */
    fun cachedAssessment(
        info: ImportedApkInfo,
        archive: File,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>
    ): ApkRuntimeAssessment? {
        val cached = cachedArchiveInspection(archive) ?: return null
        return assessmentFrom(info, cached, deviceApiLevel, supportedAbis, missingRequiredFeatures)
    }

    private fun assessmentFrom(
        info: ImportedApkInfo,
        archiveInspection: ArchiveInspection,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>
    ) = assess(
        info = info,
        archiveEntries = archiveInspection.entries,
        deviceApiLevel = deviceApiLevel,
        supportedAbis = supportedAbis,
        missingRequiredFeatures = missingRequiredFeatures,
        nativeStringReferences = archiveInspection.nativeStringReferences
    )

    private fun archiveCacheKey(archive: File): String? {
        if (!archive.isFile) return null
        return runCatching {
            "${archive.canonicalPath}|${archive.length()}|${archive.lastModified()}"
        }.getOrNull()
    }

    private fun cachedArchiveInspection(archive: File): ArchiveInspection? {
        val key = archiveCacheKey(archive) ?: return null
        return synchronized(archiveInspectionCache) { archiveInspectionCache[key] }
    }

    private fun rememberArchiveInspection(archive: File, inspection: ArchiveInspection) {
        val key = archiveCacheKey(archive) ?: return
        synchronized(archiveInspectionCache) { archiveInspectionCache[key] = inspection }
    }

    internal fun assess(
        info: ImportedApkInfo,
        archiveEntries: Collection<String>,
        deviceApiLevel: Int,
        supportedAbis: Collection<String>,
        missingRequiredFeatures: Set<String>,
        nativeStringReferences: Collection<String> = emptyList()
    ): ApkRuntimeAssessment {
        val lowerEntries = archiveEntries.map { it.lowercase() }
        val nativeLibraries = lowerEntries.filter { it.startsWith("lib/") && it.endsWith(".so") }
        val endGvrAssessment = EndGvrCompatibility.inspect(
            packageName = info.packageName,
            metadataKeys = info.vrMetadataKeys,
            metadataValues = info.vrMetadataValues.values,
            requiredFeatures = info.requiredFeatures,
            archiveEntries = lowerEntries,
            requestedPermissions = info.requestedPermissions,
            activityNames = info.activityNames,
            serviceNames = info.serviceNames,
            providerNames = info.providerNames
        )
        val engine = when {
            nativeLibraries.any { it.endsWith("/libunity.so") } ||
                lowerEntries.any { it.startsWith("assets/bin/data/") } -> "Unity"
            nativeLibraries.any { it.endsWith("/libue4.so") || it.endsWith("/libunreal.so") } ||
                lowerEntries.any { it.contains("ue4commandline") } -> "Unreal Engine"
            nativeLibraries.any { it.contains("godot") } ||
                lowerEntries.any { it.endsWith(".pck") } -> "Godot"
            nativeLibraries.isNotEmpty() -> "Собственный / неизвестный native engine"
            else -> "Не определён"
        }

        val metadata = info.vrMetadataKeys.map { it.lowercase() }
        val vendorManifestReferences = (
            info.requestedPermissions +
                info.activityNames +
                info.serviceNames +
                info.providerNames +
                info.vrMetadataKeys +
                info.vrMetadataValues.values
            ).filter(::looksLikeQuestRuntimeReference).distinct().sorted()
        val vendorRuntimeReferences = (
            vendorManifestReferences +
                nativeLibraries.map { it.substringAfterLast('/') }
                    .filter(::looksLikeQuestRuntimeReference) +
                nativeStringReferences.filter(::looksLikeQuestRuntimeReference)
            ).distinct().sorted().take(MAX_VENDOR_RUNTIME_REFERENCES)
        val questSignals = buildList {
            if (
                info.packageName.contains("oculus", ignoreCase = true) ||
                info.packageName.contains("quest", ignoreCase = true) ||
                info.packageName.contains("meta", ignoreCase = true)
            ) add("Quest/Meta package id")
            if (metadata.any { it.contains("oculus") || it.contains("quest") || it.contains("meta") }) {
                add("Quest/Meta manifest metadata")
            }
            if (nativeLibraries.any {
                    it.contains("ovrplugin") || it.contains("ovrplatformloader") ||
                        it.contains("oculus")
                }
            ) add("Oculus/Meta native library")
            if (info.requiredFeatures.any {
                    it.contains("oculus", ignoreCase = true) ||
                        it.contains("quest", ignoreCase = true) ||
                        it.contains("meta", ignoreCase = true)
                }
            ) add("Quest-specific required feature")
            if (vendorManifestReferences.isNotEmpty()) {
                add("Meta/Oculus permission or Android component reference")
            }
        }.distinct()

        val xrTechnologies = buildList {
            if (nativeLibraries.any { it.contains("openxr") } ||
                lowerEntries.any { it.contains("openxr") }
            ) add("OpenXR")
            if (questSignals.isNotEmpty()) add("Meta/Oculus XR")
            when (endGvrAssessment.classification) {
                EndGvrClassification.GEAR_VR_MARKED -> add("Gear VR (legacy)")
                EndGvrClassification.LEGACY_OCULUS_MOBILE_SDK -> add("Legacy Oculus Mobile SDK")
                EndGvrClassification.NO_GEAR_VR_EVIDENCE -> Unit
            }
            if (nativeLibraries.any { it.contains("openvr") || it.contains("steamvr") } ||
                lowerEntries.any { it.contains("steamvr") }
            ) add("OpenVR/SteamVR")
            if (xrTechnologiesEmptyFallback(info, lowerEntries)) add("Android VR feature declaration")
        }.distinct()

        val apkAbis = info.nativeAbis.distinct().sorted()
        val deviceAbis = supportedAbis.distinct().toList()
        val matchingAbis = apkAbis.filter { it in deviceAbis }
        val abiSummary = when {
            apkAbis.isEmpty() ->
                "Нативные ABI в APK не найдены; возможно, это Java/Kotlin APK или неполный split-набор."
            matchingAbis.isNotEmpty() ->
                "APK: ${apkAbis.joinToString()}. Телефон: ${deviceAbis.joinToString()}. Совпадение: ${matchingAbis.joinToString()}."
            else ->
                "APK: ${apkAbis.joinToString()}. Телефон: ${deviceAbis.joinToString()}. Совпадений нет."
        }

        val manifestReferences = (
            info.packageName.let(::listOf) +
                info.vrMetadataKeys +
                info.vrMetadataValues.values +
                info.requiredFeatures +
                info.requestedPermissions
            ).filter(::looksLikeXrReference).distinct().sorted()
        val componentReferences = (
            info.activityNames + info.serviceNames + info.providerNames
            ).filter(::looksLikeXrReference).distinct().sorted()
        val openXrExtensionReferences = nativeStringReferences
            .filter { XR_EXTENSION_PATTERN.matches(it) }
            .distinct()
            .sorted()
            .take(MAX_EXTENSION_REFERENCES)
        val advertisedOpenXrExtensions = EndXrRuntimeReadiness.openXrExtensions
            .map { it.name }
            .toSet()
        val openXrEntryPointReferences = OPENXR_ENTRY_POINT_MARKERS.filter { marker ->
            nativeStringReferences.any { it.equals(marker, ignoreCase = true) }
        }
        val graphicsApiMarkers = (
            GRAPHICS_API_MARKERS.filter { marker ->
                nativeStringReferences.any { it.equals(marker, ignoreCase = true) }
            } + info.requiredFeatures.filter {
                it.startsWith("android.hardware.vulkan.", ignoreCase = true)
            }
        ).distinct().sorted()
        val unsupportedOpenXrExtensions = openXrExtensionReferences
            .filterNot { it in advertisedOpenXrExtensions }
        val packagedLibraries = nativeLibraries
            .map { it.substringAfterLast('/') }
            .distinct()
            .sorted()
        val sdkRuntimeMarkers = (
            packagedLibraries.filter(::looksLikeSdkRuntimeLibrary) +
                manifestReferences.filter(::looksLikeSdkRuntimeReference) +
                componentReferences.filter(::looksLikeSdkRuntimeReference)
            ).distinct().sorted()
        val audit = ApkStaticAudit(
            nativeLibraries = packagedLibraries,
            sdkRuntimeMarkers = sdkRuntimeMarkers,
            manifestReferences = manifestReferences,
            metadataValues = info.vrMetadataValues.values.filter(::looksLikeXrReference).distinct().sorted(),
            componentReferences = componentReferences,
            vendorRuntimeReferences = vendorRuntimeReferences,
            openXrExtensionReferences = openXrExtensionReferences,
            openXrEntryPointReferences = openXrEntryPointReferences,
            graphicsApiMarkers = graphicsApiMarkers,
            requestedPermissions = info.requestedPermissions.distinct().sorted(),
            requiredFeatures = info.requiredFeatures.distinct().sorted(),
            declaredActivities = info.activityNames.distinct().sorted(),
            declaredServices = info.serviceNames.distinct().sorted(),
            declaredProviders = info.providerNames.distinct().sorted(),
            abiSummary = abiSummary
        )

        // A missing <uses-feature> is a device-capability warning, not proof that an
        // already-installed APK cannot be started. Keep true installation blockers
        // (minimum API and incompatible native ABI) separate from runtime features.
        val hardBlockers = buildList {
            if (info.minSdk > deviceApiLevel) {
                add("Требуется Android API ${info.minSdk}, на телефоне API $deviceApiLevel.")
            }
            if (info.nativeAbis.isNotEmpty() && info.nativeAbis.none { it in supportedAbis }) {
                add("ABI APK (${info.nativeAbis.joinToString()}) не совпадает с ABI телефона (${supportedAbis.joinToString()}).")
            }
        }
        val missingFeatureWarning = missingRequiredFeatures.takeIf { it.isNotEmpty() }?.let {
            "Не найдены заявленные uses-feature: ${it.sorted().joinToString()}. Это может повлиять на функции приложения и фильтрацию Google Play, но само по себе не доказывает запрет sideload-установки APK."
        }

        val route = when {
            hardBlockers.isNotEmpty() -> ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED
            endGvrAssessment.classification == EndGvrClassification.GEAR_VR_MARKED ->
                ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE
            // Hybrid APKs that bundle both libopenxr_loader.so and legacy libvrapi.so should
            // prioritize the managed OpenXR EndXR route unless explicit Gear VR markers exist.
            "OpenXR" in xrTechnologies -> ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE
            endGvrAssessment.classification == EndGvrClassification.LEGACY_OCULUS_MOBILE_SDK ->
                ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE
            questSignals.isNotEmpty() -> ApkLaunchRoute.QUEST_RUNTIME_REQUIRED
            xrTechnologies.isNotEmpty() -> ApkLaunchRoute.XR_SOURCE_ADAPTATION_REQUIRED
            else -> ApkLaunchRoute.ANDROID_APP_CANDIDATE
        }

        val details = buildList {
            addAll(hardBlockers)
            missingFeatureWarning?.let(::add)
            when (route) {
                ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED ->
                    add("Сначала нужно устранить ограничения устройства; установка или запуск могут завершиться ошибкой.")
                ApkLaunchRoute.QUEST_RUNTIME_REQUIRED ->
                    add("APK использует признаки Quest/Meta. На обычном Android нет системного Quest Runtime и Meta XR-сервисов; нужен отдельный порт или полноценная совместимая среда.")
                ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE ->
                    add("Обнаружены явные маркеры Samsung Gear VR. EndGVR пока выполняет только статическую классификацию: legacy runtime, сервисы и игровой запуск ещё не реализованы и не подтверждены.")
                ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE ->
                    add("Найдена libvrapi.so — признак legacy Oculus Mobile SDK. Это само по себе не доказывает, что приложение предназначено именно для Gear VR; нужно проверить манифест, сервисы и требования устройства.")
                ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE -> {
                    add("Обнаружены OpenXR-маркеры. Нужен активный Android OpenXR Runtime с поддержкой расширений и графического API. Один OpenXR Loader в NextVRera не является Runtime.")
                    if (missingRequiredFeatures.isNotEmpty()) {
                        add(
                            "Android не сообщает об обязательных функциях ${missingRequiredFeatures.sorted().joinToString()}. " +
                                "Это может помешать установке или работе функций устройства, но для уже установленного OpenXR APK NextVRera всё равно предлагает отдельный EndXR preflight. " +
                                "Успешный запуск и доступность VR-трекинга этим не гарантируются."
                        )
                    }
                    if (questSignals.isNotEmpty()) {
                        add("В этом APK также найдены Meta/Oculus компоненты. Попытка через EndXR имеет смысл только если игра действительно идёт через Khronos OpenXR Loader; отдельные Meta Runtime API и сервисы EndXR пока не заменяет.")
                    }
                }
                ApkLaunchRoute.XR_SOURCE_ADAPTATION_REQUIRED ->
                    add("Обнаружены XR-компоненты. Для телефона потребуется адаптация графики, трекинга и ввода под runtime QuestPhoneXR.")
                ApkLaunchRoute.ANDROID_APP_CANDIDATE ->
                    add("По доступным признакам это кандидат на обычную Android-установку. Стерео-режим и управление VR этим анализом не подтверждаются.")
            }
            if (engine != "Не определён") add("Обнаруженный движок: $engine.")
            if (xrTechnologies.isNotEmpty()) add("XR-признаки: ${xrTechnologies.joinToString()}.")
            if ("OpenXR" in xrTechnologies && openXrEntryPointReferences.isEmpty()) {
                add(
                    "Найден OpenXR Loader или другие OpenXR-маркеры, но известные клиентские вызовы не обнаружены в нативных библиотеках приложения. " +
                        "Это не исключает динамический вызов через собственные обёртки или удалённые символы; реальное использование подтвердит только runtime-телеметрия."
                )
            }
            if (graphicsApiMarkers.any {
                it.startsWith("vk", ignoreCase = true) ||
                    it.equals("libvulkan.so", true) ||
                    it.startsWith("android.hardware.vulkan.", ignoreCase = true)
            }) {
                add(
                    "В нативных библиотеках найдены Vulkan-маркеры: ${graphicsApiMarkers.filter {
                        it.startsWith("vk", true) || it.equals("libvulkan.so", true) ||
                            it.startsWith("android.hardware.vulkan.", true)
                    }.joinToString()}. " +
                        "Текущий EndXR предоставляет OpenGL ES binding, но не Vulkan binding. Это риск для APK, если игра выберет Vulkan как единственный графический backend; статические строки не доказывают, какой backend реально выберется."
                )
            }
            if (unsupportedOpenXrExtensions.isNotEmpty()) {
                add(
                    "В нативных библиотеках найдены ссылки на OpenXR extensions, которые EndXR сейчас не рекламирует: ${unsupportedOpenXrExtensions.joinToString()}. " +
                        "Это строки-маркеры, а не доказательство обязательности расширений; игра может отключить необязательные функции или отказаться от запуска, если расширение обязательно."
                )
            }
            addAll(EndXrRuntimeReadiness.warningsFor(xrTechnologies))
            if (questSignals.isNotEmpty()) add("Quest-признаки: ${questSignals.joinToString()}.")
            if (endGvrAssessment.gearVrMarkers.isNotEmpty()) {
                add("EndGVR Gear VR маркеры: ${endGvrAssessment.gearVrMarkers.joinToString()}.")
            }
            if (endGvrAssessment.legacyOculusMarkers.isNotEmpty()) {
                add("EndGVR legacy-маркеры: ${endGvrAssessment.legacyOculusMarkers.joinToString()}.")
            }
        }

        val findings = buildList {
            if (hardBlockers.isNotEmpty()) {
                add(
                    ApkCompatibilityFinding(
                        id = "device-hard-blocker",
                        severity = ApkCompatibilityFindingSeverity.BLOCKER,
                        title = "Обнаружено ограничение API или ABI",
                        evidence = hardBlockers.joinToString(" "),
                        impact = "APK использует Android API выше доступного уровня или содержит native-код без подходящего ABI.",
                        nextStep = "Нужна совместимая сборка APK под доступный Android API и ABI телефона."
                    )
                )
            }
            if (missingRequiredFeatures.isNotEmpty()) {
                add(
                    ApkCompatibilityFinding(
                        id = "missing-uses-feature",
                        severity = ApkCompatibilityFindingSeverity.WARNING,
                        title = "Не хватает заявленных Android-функций",
                        evidence = missingRequiredFeatures.sorted().joinToString(),
                        impact = "APK может установиться вручную, но игра может отключить функцию, завершиться с ошибкой или быть отфильтрована Google Play.",
                        nextStep = "Проверить, действительно ли игра использует эти функции во время запуска; не считать этот маркер самостоятельным запретом sideload."
                    )
                )
            }
            if (questSignals.isNotEmpty()) {
                add(
                    ApkCompatibilityFinding(
                        id = "meta-runtime-dependency",
                        severity = ApkCompatibilityFindingSeverity.WARNING,
                        title = "Найдены зависимости Meta/Oculus/Quest",
                        evidence = vendorRuntimeReferences.ifEmpty { questSignals }.joinToString(),
                        impact = "EndXR предоставляет собственный OpenXR Runtime, но не подменяет автоматически приватные Meta Runtime API, системные Quest-службы или vendor-permissions.",
                        nextStep = "Сопоставить разрешения, службы и библиотеки с реальными вызовами игры; для обязательных Meta API потребуется отдельная реализация или порт."
                    )
                )
            }
            if ("OpenXR" in xrTechnologies) {
                if (openXrExtensionReferences.isEmpty()) {
                    add(
                        ApkCompatibilityFinding(
                            id = "openxr-extension-evidence-missing",
                            severity = ApkCompatibilityFindingSeverity.INFO,
                            title = "OpenXR extensions не подтверждены статически",
                            evidence = "В ограниченном сканировании native-строк явные XR_* extension-имена не найдены.",
                            impact = "Строки могли быть удалены линкером, собраны динамически или находиться в split APK; отсутствие совпадений не означает, что игра не использует расширения.",
                            nextStep = "Проверить запрашиваемые расширения в реальном вызове xrCreateInstance через диагностический runtime-клиент."
                        )
                    )
                }
                openXrExtensionReferences.forEach { extensionName ->
                    val capability = EndXrRuntimeReadiness.openXrExtensions.firstOrNull {
                        it.name == extensionName
                    }
                    if (capability == null) {
                        add(
                            ApkCompatibilityFinding(
                                id = "unsupported-openxr-extension",
                                severity = ApkCompatibilityFindingSeverity.WARNING,
                                title = "OpenXR extension не рекламируется EndXR",
                                evidence = extensionName,
                                impact = "Если игра требует это расширение при создании instance, EndXR отклонит такой запрос. Статическая строка не доказывает, что расширение обязательно.",
                                nextStep = "Проверить, запрашивает ли игра расширение реально и обязательно ли оно; затем реализовывать только подтверждённый API-контракт."
                            )
                        )
                    } else {
                        val partial = capability.state == RuntimeCapabilityState.PARTIAL
                        add(
                            ApkCompatibilityFinding(
                                id = if (partial) "partial-openxr-extension" else "available-openxr-extension",
                                severity = if (partial) ApkCompatibilityFindingSeverity.WARNING else ApkCompatibilityFindingSeverity.INFO,
                                title = if (partial) "OpenXR extension поддерживается частично" else "OpenXR extension есть в EndXR",
                                evidence = extensionName,
                                impact = capability.details,
                                nextStep = "Считать это предварительным совпадением API, а не подтверждением работы игры; нужен внешний запуск и runtime-телеметрия."
                            )
                        )
                    }
                }
                val vulkanMarkers = graphicsApiMarkers.filter {
                    it.startsWith("vk", ignoreCase = true) || it.equals("libvulkan.so", true) ||
                        it.startsWith("android.hardware.vulkan.", ignoreCase = true)
                }
                if (vulkanMarkers.isNotEmpty()) {
                    add(
                        ApkCompatibilityFinding(
                            id = "vulkan-backend-risk",
                            severity = ApkCompatibilityFindingSeverity.WARNING,
                            title = "В APK найдены Vulkan-маркеры",
                            evidence = vulkanMarkers.joinToString(),
                            impact = "EndXR сейчас предоставляет OpenGL ES binding, а не Vulkan binding. Маркеры могут принадлежать неактивному backend; они не доказывают, что игра выберет Vulkan.",
                            nextStep = "Проверить renderer/backend в настройках игры или runtime-логе. Для Vulkan-only игры потребуется Vulkan binding в EndXR."
                        )
                    )
                }
                add(
                    ApkCompatibilityFinding(
                        id = "endxr-input-limitations",
                        severity = ApkCompatibilityFindingSeverity.WARNING,
                        title = "Ввод EndXR пока не эквивалентен контроллерам Quest",
                        evidence = "MediaPipe hand tracking и IPC-контракт EndXR; внешний игровой процесс ещё не подтверждён.",
                        impact = "Поза рук приблизительная и не имеет TRACKED-флагов; настоящие 6DoF-контроллеры, физические кнопки/sticks и haptics не реализованы. Совместимость зависит от OpenXR actions и interaction profiles, которые запрашивает игра.",
                        nextStep = "После подтверждения xrCreateInstance проверить input actions и frame telemetry; для конкретной игры может понадобиться отдельная схема bindings или адаптер."
                    )
                )
                add(
                    ApkCompatibilityFinding(
                        id = "external-runtime-unverified",
                        severity = ApkCompatibilityFindingSeverity.INFO,
                        title = "Внешний запуск ещё не доказан",
                        evidence = "Sandbox проверяет bundled runtime внутри NextVRera, но не выбор EndXR Android RuntimeBroker отдельным процессом игры.",
                        impact = "Статический анализ не подтверждает xrCreateInstance, создание сессии или вывод игровых кадров.",
                        nextStep = "Провести запуск установленной игры через управляемый preflight и проверить runtime-телеметрию instance/session/frame."
                    )
                )
            }
            if (route == ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE ||
                route == ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE
            ) {
                add(
                    ApkCompatibilityFinding(
                        id = "legacy-oculus-runtime",
                        severity = ApkCompatibilityFindingSeverity.WARNING,
                        title = "Обнаружен legacy Oculus/Gear VR runtime",
                        evidence = (audit.sdkRuntimeMarkers + audit.componentReferences)
                            .filter { it.contains("vrapi", true) || it.contains("samsung", true) || it.contains("oculus", true) }
                            .distinct().joinToString().ifBlank { route.label },
                        impact = "EndXR автоматически не реализует старый Oculus Mobile/Gear VR API. EndGVR остаётся отдельным путём исследования, а не подтверждённой средой запуска.",
                        nextStep = "Определить точный VR SDK и сервисный контракт; наличие libvrapi.so само по себе не доказывает совместимость."
                    )
                )
            }
            if (isEmpty()) {
                add(
                    ApkCompatibilityFinding(
                        id = "no-static-blocker-found",
                        severity = ApkCompatibilityFindingSeverity.INFO,
                        title = "Явный блокер статически не найден",
                        evidence = "APK-манифест и ограниченный анализ архива не выявили известных несовместимостей.",
                        impact = "Это не подтверждает, что приложение запускается или поддерживает VR на телефоне.",
                        nextStep = "Проверить обычный Android-запуск и отдельно XR-поведение на устройстве."
                    )
                )
            }
        }

        return ApkRuntimeAssessment(route, engine, xrTechnologies, details, audit, findings)
    }

    private fun inspectArchive(archive: File): ArchiveInspection {
        if (!archive.isFile) return ArchiveInspection(emptyList(), emptyList())
        return runCatching {
            ZipFile(archive).use { zip ->
                val entries = mutableListOf<String>()
                val references = sortedSetOf<String>()
                var remainingBudget = MAX_TOTAL_NATIVE_SCAN_BYTES
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    entries += entry.name
                    val normalizedName = entry.name.lowercase()
                    val isAppNativeLibrary = normalizedName.startsWith("lib/") &&
                        normalizedName.endsWith(".so") &&
                        !normalizedName.endsWith("/libopenxr_loader.so")
                    if (isAppNativeLibrary && remainingBudget > 0L) {
                        val byteLimit = minOf(
                            MAX_NATIVE_SCAN_BYTES_PER_LIBRARY,
                            remainingBudget
                        )
                        val scannedBytes = runCatching {
                            zip.getInputStream(entry).use { input ->
                                scanNativeLibrary(input, byteLimit, references)
                            }
                        }.getOrDefault(0L)
                        remainingBudget -= scannedBytes
                    }
                }
                ArchiveInspection(entries, references.toList())
            }
        }.getOrDefault(ArchiveInspection(emptyList(), emptyList()))
    }

    /**
     * Scans bounded chunks only. This is a heuristic over ELF string tables and
     * read-only data; it does not prove that a referenced API or XR extension is
     * required, loaded, or selected at runtime.
     */
    private fun scanNativeLibrary(
        input: InputStream,
        byteLimit: Long,
        references: MutableSet<String>
    ): Long {
        val buffer = ByteArray(32 * 1024)
        var scannedBytes = 0L
        var carry = ""
        while (scannedBytes < byteLimit) {
            val requested = minOf(buffer.size.toLong(), byteLimit - scannedBytes).toInt()
            val read = input.read(buffer, 0, requested)
            if (read <= 0) break
            scannedBytes += read
            val chunk = String(buffer, 0, read, StandardCharsets.ISO_8859_1)
            val searchable = carry + chunk
            XR_EXTENSION_PATTERN.findAll(searchable).forEach { match ->
                if (references.count { it.startsWith("XR_") } < MAX_EXTENSION_REFERENCES) {
                    references += match.value
                }
            }
            val lower = searchable.lowercase()
            GRAPHICS_API_MARKERS.forEach { marker ->
                if (lower.contains(marker.lowercase())) references += marker
            }
            OPENXR_ENTRY_POINT_MARKERS.forEach { marker ->
                if (lower.contains(marker.lowercase())) references += marker
            }
            carry = searchable.takeLast(MAX_STRING_CARRY)
        }
        return scannedBytes
    }

    private const val MAX_VENDOR_RUNTIME_REFERENCES = 64

    private fun looksLikeQuestRuntimeReference(value: String): Boolean {
        val normalized = value.lowercase()
        return listOf(
            "com.oculus.",
            "com.meta.",
            "oculus",
            "quest",
            "ovrplugin",
            "ovrplatformloader",
            "meta.xr"
        ).any(normalized::contains)
    }

    private fun looksLikeXrReference(value: String): Boolean {
        val normalized = value.lowercase()
        return listOf(
            "samsung.android.vr", "samsung.android.hmt", "gearvr", "gear_vr",
            "gear-vr", "oculus", "openxr", "openvr", "steamvr", "quest", "meta"
        ).any { marker -> normalized.contains(marker) }
    }

    private fun looksLikeSdkRuntimeLibrary(name: String): Boolean {
        val normalized = name.lowercase()
        return listOf(
            "vrapi", "ovr", "oculus", "openxr", "openvr", "steamvr"
        ).any { marker -> normalized.contains(marker) }
    }

    private fun looksLikeSdkRuntimeReference(value: String): Boolean {
        val normalized = value.lowercase()
        return listOf(
            "vrapi", "ovr", "oculus", "samsung.android.hmt",
            "samsung.android.vr", "gearvr", "gear_vr", "gear-vr",
            "openxr", "openvr", "steamvr"
        ).any { marker -> normalized.contains(marker) }
    }

    private fun xrTechnologiesEmptyFallback(
        info: ImportedApkInfo,
        lowerEntries: List<String>
    ): Boolean = info.requiredFeatures.any { it.contains("vr", ignoreCase = true) } ||
        lowerEntries.any { it.contains("vrmanifest") }
}
