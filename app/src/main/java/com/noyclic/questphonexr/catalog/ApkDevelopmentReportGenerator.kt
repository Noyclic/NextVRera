package com.noyclic.questphonexr.catalog

import com.noyclic.questphonexr.runtime.EndXrRuntimeReadiness
import com.noyclic.questphonexr.runtime.RuntimeCapability
import com.noyclic.questphonexr.runtime.RuntimeOpenXrExtensionCapability

/**
 * A pure snapshot of APK analysis and device state, so report generation can be unit-tested
 * without Android framework services or an installed game.
 */
internal data class ApkDevelopmentReportContext(
    val info: ImportedApkInfo,
    val assessment: ApkRuntimeAssessment,
    val deviceModel: String,
    val androidRelease: String,
    val deviceApiLevel: Int,
    val supportedAbis: List<String>,
    val installedVersionCode: Long?,
    val runtimePermissionStates: Map<String, Boolean?>
) {
    val importedVersionIsInstalled: Boolean
        get() = installedVersionCode == info.versionCode
}

/** Builds copy-ready development artifacts from the exact data collected by the APK analyzer. */
internal object ApkDevelopmentReportGenerator {
    fun aiTaskPrompt(context: ApkDevelopmentReportContext): String = buildString {
        appendLine("# Задание для разработки совместимости APK с NextVRera / EndXR")
        appendLine()
        appendLine("Репозиторий: https://github.com/Noyclic/NextVRera")
        appendLine("Рабочая ветка: main")
        appendLine("Цель: исследовать и, если это подтверждается реальными API-вызовами, улучшить совместимость указанного APK с телефонным EndXR.")
        appendLine()
        appendLine("## Обнаруженный APK")
        appendLine("Название: " + context.info.displayName)
        appendLine("Пакет: " + context.info.packageName)
        appendLine("Файл: " + context.info.originalName)
        appendLine("Версия: " + context.info.versionName.ifBlank { "не указана" } + " (" + context.info.versionCode + ")")
        appendLine("SHA-256: " + context.info.sha256.ifBlank { "не вычислен" })
        appendLine("Маршрут анализатора: " + context.assessment.route.label)
        appendLine("Движок: " + context.assessment.engine)
        appendLine("XR-технологии: " + display(context.assessment.xrTechnologies))
        appendLine("minSdk / targetSdk: " + context.info.minSdk + " / " + context.info.targetSdk)
        appendLine("Native ABI в APK: " + display(context.info.nativeAbis))
        appendLine("Устройство: " + context.deviceModel + " · Android " + context.androidRelease + " · API " + context.deviceApiLevel)
        appendLine("ABI устройства: " + display(context.supportedAbis))
        appendLine()
        appendLine("## Конкретные статические сигналы и findings")
        if (context.assessment.findings.isEmpty()) {
            appendLine("- Структурированные findings не сформированы.")
        } else {
            context.assessment.findings.forEach { finding ->
                appendLine("- [" + finding.severity.label + "] " + finding.title)
                appendLine("  Доказательство: " + clipped(finding.evidence, 420))
                appendLine("  Практический эффект: " + clipped(finding.impact, 420))
                appendLine("  Следующая проверка: " + clipped(finding.nextStep, 420))
            }
        }
        appendLine()
        appendLine("Обнаруженные OpenXR extension-строки: " + display(context.assessment.audit.openXrExtensionReferences, 24))
        appendLine("Обнаруженные OpenXR entry-point строки: " + display(context.assessment.audit.openXrEntryPointReferences, 24))
        appendLine("Графические API-маркеры: " + display(context.assessment.audit.graphicsApiMarkers, 24))
        appendLine("Meta/Oculus/Quest-ссылки: " + display(context.assessment.audit.vendorRuntimeReferences, 24))
        appendLine("Нативные библиотеки: " + display(context.assessment.audit.nativeLibraries, 32))
        appendLine("Разрешения из манифеста: " + display(context.info.requestedPermissions, 32))
        appendLine("uses-feature: " + display(context.info.requiredFeatures, 24))
        appendLine()
        appendLine("## Уже существующие возможности EndXR — повторно не реализовывать")
        EndXrRuntimeReadiness.capabilities.forEach { capability ->
            appendLine("- " + capability.title + " — " + capability.state.label + ": " + clipped(capability.details, 360))
        }
        appendLine("Текущие extension-возможности:")
        EndXrRuntimeReadiness.openXrExtensions.forEach { extension ->
            appendLine("- " + extension.name + " — " + extension.state.label + ": " + clipped(extension.details, 360))
        }
        appendLine()
        appendLine("Важно учитывать:")
        appendLine("- Проверка Sandbox / bundled runtime внутри процесса NextVRera не доказывает, что отдельный процесс игры выберет EndXR через RuntimeBroker.")
        appendLine("- XR_EXT_hand_tracking и XR_EXT_hand_interaction реализованы частично. Положение кисти и суставы оцениваются камерой; это не TRACKED 6DoF-контроллеры.")
        appendLine("- OpenXR Actions, pinch/grasp/aim input и защищённый IPC-контракт уже существуют. Не создавать второй параллельный канал ввода и не дублировать готовые action/pose API.")
        appendLine("- EndXR сейчас использует OpenGL ES binding; Vulkan binding не заявлен.")
        appendLine("- Состояние внешнего игрового процесса, реальный вызов xrCreateInstance, выбранный backend и отрисовка кадров не считаются подтверждёнными только по статическому анализу APK.")
        appendLine()
        appendLine("## Что нужно сделать")
        appendLine("1. Перед изменениями проверь актуальный HEAD и состояние main. Изучи существующие анализатор, capability registry, RuntimeBroker, JNI, IPC, OpenXR actions и Sandbox-тесты.")
        appendLine("2. Проследи реальные точки использования обнаруженных зависимостей. Отличай обязательный API от необязательного плагина, неиспользуемой native-строки или альтернативного renderer.")
        appendLine("3. Составь приоритетный план по findings выше. Сначала устрани подтверждённые блокирующие несовместимости; спорные сигналы оставь диагностическими, пока не получено runtime-доказательство.")
        appendLine("4. Реализуй один цельный, хорошо интегрированный патч: переиспользуй текущие классы и контракты, не создавай дублирующие подсистемы и не разбивай работу на множество косметических микропатчей.")
        appendLine("5. Добавь unit-тесты для классификации/форматирования и native/runtime-тесты для новых OpenXR-контрактов. Где возможно, добавь отдельный тест через внешний процесс, а не только Sandbox.")
        appendLine("6. Обнови документацию и явно перечисли, что реально проверено, что проверено только внутри процесса, а что осталось NOT_VERIFIED.")
        appendLine()
        appendLine("## Требования к корректности")
        appendLine("- Не подменять или обходить signature/privileged Meta permissions и не выдавать vendor API за реализованный без реального контракта.")
        appendLine("- Не считать наличие permission в AndroidManifest доказательством запроса или выдачи разрешения.")
        appendLine("- Не считать найденную строку XR extension доказательством обязательного запроса; проверь фактический список, переданный в xrCreateInstance.")
        appendLine("- Не объявлять отсутствие uses-feature автоматическим запретом sideload-установки.")
        appendLine("- Не добавлять фиктивные controller buttons, stick axes, haptics, TRACKED flags или 6DoF.")
        appendLine("- Не менять успешно работающий базовый runtime path без регрессионных тестов.")
        appendLine()
        appendLine("## Критерии приёмки")
        appendLine("- Каждое новое поведение покрыто тестом; существующие тесты APK audit, native bootstrap, OpenXR и Sandbox остаются зелёными.")
        appendLine("- У каждого нового capability есть корректное состояние VERIFIED_IN_PROCESS / PARTIAL / NOT_IMPLEMENTED / NOT_VERIFIED и описание области проверки.")
        appendLine("- Диагностика показывает фактическую причину отказа и следующий шаг, а не выдуманный процент совместимости.")
        appendLine("- Если игра не может быть запущена без отсутствующего API, отчёт честно оставляет её неподдерживаемой и указывает минимальную следующую реализацию.")
        appendLine("- В итоговом резюме перечислены изменённые файлы, тесты и точный статус CI; не заявляй о внешней совместимости без реального запуска APK.")
        appendLine()
        appendLine("## Полные технические данные")
        append(technicalReport(context))
    }

    fun technicalReport(context: ApkDevelopmentReportContext): String = buildString {
        appendLine("NextVRera — технический APK-отчёт для разработчика")
        appendLine("==============================================")
        appendLine()
        appendApkIdentity(this, context)
        appendLine("Маршрут совместимости: " + context.assessment.route.label)
        appendLine("Движок: " + context.assessment.engine)
        appendLine("XR-технологии: " + display(context.assessment.xrTechnologies))
        appendLine("ABI-анализ: " + context.assessment.audit.abiSummary)
        appendLine()
        appendLine("Состояние установки")
        appendLine("- Версия из APK: " + context.info.versionCode)
        appendLine("- Версия пакета, найденная в Android: " + (context.installedVersionCode?.toString() ?: "не обнаружена / неизвестна"))
        appendLine("- Та же версия установлена: " + yesNo(context.importedVersionIsInstalled))
        appendLine()
        appendLine("Разрешения Android из манифеста")
        val runtimePermissionSet = ApkPermissionDiagnostics.runtimePermissions(context.info.requestedPermissions).toSet()
        if (context.info.requestedPermissions.isEmpty()) {
            appendLine("- Не перечислены")
        } else {
            context.info.requestedPermissions.forEach { permission ->
                val state = when {
                    permission !in runtimePermissionSet -> "объявлено; фактический grant этим отчётом не проверялся"
                    !context.importedVersionIsInstalled -> "состояние неизвестно: точная импортированная версия не установлена"
                    context.runtimePermissionStates[permission] == true -> "разрешено для установленной версии"
                    context.runtimePermissionStates[permission] == false -> "не разрешено (или ещё не запрошено игрой)"
                    else -> "состояние не проверено"
                }
                appendLine("- " + permission + " — " + state)
            }
        }
        appendLine()
        appendEntries(this, "Отслеживаемые Android runtime permissions", ApkPermissionDiagnostics.runtimePermissions(context.info.requestedPermissions))
        appendEntries(this, "Meta / Oculus / Quest / Samsung permissions", ApkPermissionDiagnostics.vendorPermissions(context.info.requestedPermissions))
        appendEntries(this, "Обязательные uses-feature", context.info.requiredFeatures)
        appendLine()
        appendEntries(this, "Activities", context.info.activityNames)
        appendEntries(this, "Services", context.info.serviceNames)
        appendEntries(this, "Providers", context.info.providerNames)
        appendLine()
        appendEntries(this, "Нативные библиотеки", context.assessment.audit.nativeLibraries)
        appendEntries(this, "SDK / Runtime-маркеры", context.assessment.audit.sdkRuntimeMarkers)
        appendEntries(this, "VR-маркеры манифеста", context.assessment.audit.manifestReferences)
        appendEntries(this, "VR-значения metadata", context.assessment.audit.metadataValues)
        appendEntries(this, "VR-компоненты", context.assessment.audit.componentReferences)
        appendEntries(this, "Meta/Oculus runtime references", context.assessment.audit.vendorRuntimeReferences)
        appendEntries(this, "OpenXR extension-строки", context.assessment.audit.openXrExtensionReferences)
        appendEntries(this, "OpenXR entry-point строки", context.assessment.audit.openXrEntryPointReferences)
        appendEntries(this, "Графические API-маркеры", context.assessment.audit.graphicsApiMarkers)
        appendLine()
        appendFindings(this, context.assessment)
        appendRuntimeInventory(this)
        appendLine()
        appendLine("Ограничения и дополнительные сведения анализатора")
        if (context.assessment.details.isEmpty()) appendLine("- Не указаны")
        else context.assessment.details.forEach { appendLine("- " + clipped(it, 700)) }
        appendLine()
        appendStaticDisclaimer(this)
    }

    fun fullReport(context: ApkDevelopmentReportContext): String = buildString {
        append(technicalReport(context))
        appendLine()
        appendLine("Полный набор данных статического APK-аудита")
        appendLine("------------------------------------------")
        appendEntries(this, "Все запрошенные permissions", context.assessment.audit.requestedPermissions, 200)
        appendEntries(this, "Все uses-feature", context.assessment.audit.requiredFeatures, 200)
        appendEntries(this, "Все declared Activities", context.assessment.audit.declaredActivities, 200)
        appendEntries(this, "Все declared Services", context.assessment.audit.declaredServices, 200)
        appendEntries(this, "Все declared Providers", context.assessment.audit.declaredProviders, 200)
        appendEntries(this, "Metadata keys", context.info.vrMetadataKeys, 200)
        if (context.info.vrMetadataValues.isEmpty()) {
            appendLine("Metadata values: не обнаружены")
        } else {
            appendLine("Metadata values:")
            context.info.vrMetadataValues.toSortedMap().forEach { (key, value) ->
                appendLine("- " + clipped(key, 180) + " = " + clipped(value, 512))
            }
        }
        appendLine()
        appendLine("Подробные findings")
        appendFindings(this, context.assessment)
        appendLine()
        appendLine("Полный инвентарь возможностей EndXR")
        appendRuntimeInventory(this)
        appendStaticDisclaimer(this)
    }

    private fun appendApkIdentity(out: StringBuilder, context: ApkDevelopmentReportContext) {
        out.appendLine("APK")
        out.appendLine("- Название: " + context.info.displayName)
        out.appendLine("- Пакет: " + context.info.packageName)
        out.appendLine("- Файл: " + context.info.originalName)
        out.appendLine("- Версия: " + context.info.versionName.ifBlank { "не указана" } + " (" + context.info.versionCode + ")")
        out.appendLine("- Размер: " + formatSize(context.info.sizeBytes))
        out.appendLine("- SHA-256: " + context.info.sha256.ifBlank { "не вычислен" })
        out.appendLine("- minSdk / targetSdk: " + context.info.minSdk + " / " + context.info.targetSdk)
        out.appendLine()
        out.appendLine("Устройство")
        out.appendLine("- Модель: " + context.deviceModel)
        out.appendLine("- Android: " + context.androidRelease + " · API " + context.deviceApiLevel)
        out.appendLine("- Поддерживаемые ABI: " + display(context.supportedAbis))
        out.appendLine()
    }

    private fun appendFindings(out: StringBuilder, assessment: ApkRuntimeAssessment) {
        out.appendLine("Findings карты совместимости EndXR")
        if (assessment.findings.isEmpty()) {
            out.appendLine("- Структурированные findings не обнаружены")
            return
        }
        assessment.findings.forEach { finding ->
            out.appendLine("[" + finding.severity.label + "] " + finding.title + " (" + finding.id + ")")
            out.appendLine("  Доказательства: " + clipped(finding.evidence, 900))
            out.appendLine("  Влияние: " + clipped(finding.impact, 900))
            out.appendLine("  Следующий шаг: " + clipped(finding.nextStep, 900))
        }
    }

    private fun appendRuntimeInventory(out: StringBuilder) {
        out.appendLine("OpenXR extensions, заявленные текущим EndXR registry")
        appendExtensions(out, EndXrRuntimeReadiness.openXrExtensions)
        out.appendLine()
        out.appendLine("Возможности EndXR")
        appendCapabilities(out, EndXrRuntimeReadiness.capabilities)
        out.appendLine()
        out.appendLine("Область проверки: VERIFIED_IN_PROCESS относится к bundled/native tests и Sandbox. Она не подтверждает RuntimeBroker selection или запуск сторонней игры.")
    }

    private fun appendExtensions(out: StringBuilder, extensions: List<RuntimeOpenXrExtensionCapability>) {
        if (extensions.isEmpty()) {
            out.appendLine("- Не перечислены")
            return
        }
        extensions.forEach { extension ->
            out.appendLine("- " + extension.name + " — " + extension.state.label)
            out.appendLine("  " + clipped(extension.details, 900))
        }
    }

    private fun appendCapabilities(out: StringBuilder, capabilities: List<RuntimeCapability>) {
        if (capabilities.isEmpty()) {
            out.appendLine("- Не перечислены")
            return
        }
        capabilities.forEach { capability ->
            out.appendLine("- " + capability.title + " [" + capability.id + "] — " + capability.state.label)
            out.appendLine("  " + clipped(capability.details, 1200))
        }
    }

    private fun appendEntries(
        out: StringBuilder,
        label: String,
        values: List<String>,
        limit: Int = 80
    ) {
        out.appendLine(label + ":")
        if (values.isEmpty()) {
            out.appendLine("  - не обнаружено")
            return
        }
        values.take(limit).forEach { out.appendLine("  - " + clipped(it, 512)) }
        if (values.size > limit) out.appendLine("  - ... пропущено ещё " + (values.size - limit) + " записей")
    }

    private fun appendStaticDisclaimer(out: StringBuilder) {
        out.appendLine()
        out.appendLine("ВАЖНО: APK анализирован статически. Наличие строки или permission не доказывает реальный вызов API, обязательность расширения, факт запроса/выдачи разрешения или успешный запуск. Отчёт не подтверждает создание внешней OpenXR-сессии, выбор RuntimeBroker, вывод кадров, ввод или производительность игры.")
        out.appendLine("Vendor permissions могут требовать подписи или системных привилегий; обычное приложение не может выдать их настройками Android.")
        out.appendLine("Отсутствующий uses-feature сам по себе не доказывает запрет установки APK через sideload.")
    }

    private fun display(values: List<String>, limit: Int = 40): String {
        if (values.isEmpty()) return "не обнаружено"
        val displayed = values.take(limit).joinToString()
        return if (values.size > limit) displayed + " … (ещё " + (values.size - limit) + ")" else displayed
    }

    private fun clipped(value: String, maxLength: Int): String {
        val normalized = value.replace('\r', ' ').replace('\n', ' ').trim()
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength) + "…"
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"

    private fun formatSize(bytes: Long): String =
        String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))
}
