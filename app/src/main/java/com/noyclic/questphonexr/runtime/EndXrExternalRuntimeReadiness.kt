package com.noyclic.questphonexr.runtime

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
        private const val INSTALLABLE_BROKER = "org.khronos.openxr.runtime_broker"
        private const val SYSTEM_BROKER = "org.khronos.openxr.system_runtime_broker"

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
                if (!e.brokerProviderVisible) add("selected RuntimeBroker provider is not visible")
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
            return EndXrExternalRuntimeReadiness(
                verdict, e.metadataReady, e.brokerSelectionReady, e.openXrProbePass, blockers, warnings
            )
        }

        fun fromBrokerProbe(
            report: RuntimeBrokerProbeReport,
            nativeProbeLabel: String,
            ownPackageName: String,
            expectedMajorVersion: Int = 1,
        ): EndXrExternalRuntimeReadiness {
            // The loader report already records which broker wins Khronos precedence.
            // Inspect that provider's status only; a failed installable query must not
            // hide a visible system broker, and a failed selected query must remain visible.
            val selectedStatus = when (report.loaderSelectedBroker) {
                INSTALLABLE_BROKER -> report.installableBrokerStatus
                SYSTEM_BROKER -> report.systemBrokerStatus
                else -> null
            }
            val providerVisible = selectedStatus != null &&
                !selectedStatus.contains("provider not found or not visible")
            val queryCompleted = selectedStatus?.contains("active runtime query=") == true &&
                !selectedStatus.contains("query failed:")

            return evaluate(EndXrExternalRuntimeReadinessEvidence(
                ownServiceFound = report.ownRuntimeServiceFound,
                ownServiceExported = report.ownRuntimeServiceExported,
                majorVersion = report.majorVersion,
                expectedMajorVersion = expectedMajorVersion,
                soFilenamePresent = !report.soFilename.isNullOrBlank(),
                nativeLibraryExists = report.nativeLibraryExists,
                brokerProviderVisible = providerVisible,
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
