package com.noyclic.questphonexr.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDevelopmentReportGeneratorTest {
    private fun context(
        installedVersionCode: Long? = null,
        permissionStates: Map<String, Boolean?> = mapOf("android.permission.CAMERA" to null)
    ): ApkDevelopmentReportContext {
        val info = ImportedApkInfo(
            id = "fixture",
            fileName = "sample_quest.apk",
            originalName = "sample_quest.apk",
            displayName = "Sample Quest Game",
            packageName = "com.example.questgame",
            versionName = "2.4.1",
            versionCode = 24L,
            minSdk = 29,
            targetSdk = 35,
            nativeAbis = listOf("arm64-v8a"),
            requiredFeatures = listOf("android.hardware.vr.headtracking"),
            vrMetadataKeys = listOf("com.oculus.vr.application.mode"),
            sizeBytes = 1_048_576L,
            importedAtEpochMs = 1L,
            requestedPermissions = listOf(
                "android.permission.CAMERA",
                "com.oculus.permission.USE_SCENE"
            ),
            activityNames = listOf("com.example.questgame.MainActivity"),
            serviceNames = listOf("com.oculus.vr.OVRService"),
            providerNames = listOf("com.example.questgame.GameProvider"),
            sha256 = "a".repeat(64),
            vrMetadataValues = mapOf("com.oculus.vr.application.mode" to "vr")
        )
        val audit = ApkStaticAudit(
            nativeLibraries = listOf("libopenxr_loader.so", "libunity.so", "libgame.so"),
            sdkRuntimeMarkers = listOf("libopenxr_loader.so"),
            manifestReferences = listOf("com.oculus.vr.application.mode"),
            metadataValues = listOf("vr"),
            componentReferences = listOf("com.oculus.vr.OVRService"),
            vendorRuntimeReferences = listOf("com.oculus.permission.USE_SCENE"),
            openXrExtensionReferences = listOf("XR_KHR_vulkan_enable", "XR_EXT_hand_tracking"),
            openXrEntryPointReferences = listOf("xrCreateInstance", "xrCreateSession"),
            graphicsApiMarkers = listOf("vkCreateInstance", "libvulkan.so"),
            requestedPermissions = info.requestedPermissions,
            requiredFeatures = info.requiredFeatures,
            declaredActivities = info.activityNames,
            declaredServices = info.serviceNames,
            declaredProviders = info.providerNames,
            abiSummary = "APK arm64-v8a; устройство arm64-v8a"
        )
        val finding = ApkCompatibilityFinding(
            id = "unsupported-openxr-extension",
            severity = ApkCompatibilityFindingSeverity.WARNING,
            title = "OpenXR extension не рекламируется EndXR",
            evidence = "XR_KHR_vulkan_enable",
            impact = "Если игра требует это расширение, запрос может завершиться ошибкой.",
            nextStep = "Проверить фактический список расширений в xrCreateInstance."
        )
        val assessment = ApkRuntimeAssessment(
            route = ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE,
            engine = "Unity",
            xrTechnologies = listOf("OpenXR", "Meta/Oculus XR"),
            details = listOf("Sandbox runtime path is verified in-process; third-party game process is not verified."),
            audit = audit,
            findings = listOf(finding)
        )
        return ApkDevelopmentReportContext(
            info = info,
            assessment = assessment,
            deviceModel = "Redmi Note 13 4G",
            androidRelease = "15",
            deviceApiLevel = 35,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            installedVersionCode = installedVersionCode,
            runtimePermissionStates = permissionStates
        )
    }

    @Test
    fun aiPromptIncludesTheActualApkSignalsAndExistingEndXrLimits() {
        val report = ApkDevelopmentReportGenerator.aiTaskPrompt(context())

        assertTrue(report.contains("com.example.questgame"))
        assertTrue(report.contains("XR_KHR_vulkan_enable"))
        assertTrue(report.contains("com.oculus.permission.USE_SCENE"))
        assertTrue(report.contains("не создавать второй параллельный канал ввода"))
        assertTrue(report.contains("RuntimeBroker"))
        assertTrue(report.contains("Критерии приёмки"))
        assertFalse(report.contains("100% совместимость"))
    }

    @Test
    fun technicalReportSeparatesManifestPermissionsFromRuntimeGrantState() {
        val unknown = ApkDevelopmentReportGenerator.technicalReport(context())
        assertTrue(unknown.contains("android.permission.CAMERA"))
        assertTrue(unknown.contains("состояние неизвестно"))
        assertTrue(unknown.contains("com.oculus.permission.USE_SCENE"))
        assertTrue(unknown.contains("signature/privileged") || unknown.contains("Vendor permissions"))

        val denied = ApkDevelopmentReportGenerator.technicalReport(
            context(installedVersionCode = 24L, permissionStates = mapOf("android.permission.CAMERA" to false))
        )
        assertTrue(denied.contains("не разрешено (или ещё не запрошено)"))
    }

    @Test
    fun fullReportIncludesAllManifestComponentsFindingsAndEndXrCapabilities() {
        val report = ApkDevelopmentReportGenerator.fullReport(context())

        assertTrue(report.contains("com.example.questgame.MainActivity"))
        assertTrue(report.contains("com.oculus.vr.OVRService"))
        assertTrue(report.contains("com.example.questgame.GameProvider"))
        assertTrue(report.contains("XR_KHR_vulkan_enable"))
        assertTrue(report.contains("Подробные findings"))
        assertTrue(report.contains("OpenXR extensions, заявленные текущим EndXR registry"))
        assertTrue(report.contains("6DoF position tracking"))
        assertTrue(report.contains("RuntimeBroker"))
        assertTrue(report.contains("статически"))
    }

    @Test
    fun emptyFieldsAreReportedAsNotDetectedRatherThanInvented() {
        val minimal = context().copy(
            info = context().info.copy(
                requestedPermissions = emptyList(),
                nativeAbis = emptyList(),
                requiredFeatures = emptyList(),
                activityNames = emptyList(),
                serviceNames = emptyList(),
                providerNames = emptyList(),
                vrMetadataKeys = emptyList(),
                vrMetadataValues = emptyMap()
            ),
            assessment = context().assessment.copy(audit = ApkStaticAudit())
        )
        val report = ApkDevelopmentReportGenerator.technicalReport(minimal)

        assertTrue(report.contains("не обнаружено"))
        assertTrue(report.contains("не перечислены") || report.contains("не обнаружено"))
    }
}
