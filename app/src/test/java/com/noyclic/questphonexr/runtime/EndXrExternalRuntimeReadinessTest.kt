package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrExternalRuntimeReadinessTest {
    private fun evidence(
        brokerVisible: Boolean = true,
        brokerCompleted: Boolean = true,
        selectedPackage: String? = "com.noyclic.questphonexr",
        selectedLibraryExists: Boolean = true,
        probePass: Boolean = true,
        probeFail: Boolean = false,
        serviceFound: Boolean = true,
        serviceExported: Boolean = true,
        majorVersion: Int? = 1,
        soFilename: Boolean = true,
        libraryExists: Boolean = true,
    ) = EndXrExternalRuntimeReadinessEvidence(
        ownServiceFound = serviceFound,
        ownServiceExported = serviceExported,
        majorVersion = majorVersion,
        expectedMajorVersion = 1,
        soFilenamePresent = soFilename,
        nativeLibraryExists = libraryExists,
        brokerProviderVisible = brokerVisible,
        brokerQueryCompleted = brokerCompleted,
        brokerSelectedRuntimePackage = selectedPackage,
        brokerSelectedRuntimeLibraryExists = selectedLibraryExists,
        ownPackageName = "com.noyclic.questphonexr",
        openXrProbePass = probePass,
        openXrProbeFail = probeFail,
    )

    @Test
    fun fullyVerifiedExternalPathIsReady() {
        val result = EndXrExternalRuntimeReadiness.evaluate(evidence())
        assertEquals(EndXrExternalRuntimeReadinessVerdict.READY, result.verdict)
        assertTrue(result.metadataReady)
        assertTrue(result.brokerSelectionReady)
        assertTrue(result.nativeProbeReady)
    }

    @Test
    fun missingBrokerIsDegradedNotReady() {
        val result = EndXrExternalRuntimeReadiness.evaluate(evidence(brokerVisible = false))
        assertEquals(EndXrExternalRuntimeReadinessVerdict.DEGRADED, result.verdict)
        assertTrue(result.warnings.any { it.contains("RuntimeBroker") })
    }

    @Test
    fun failedBrokerQueryIsNotMisreportedAsMissingProvider() {
        val result = EndXrExternalRuntimeReadiness.evaluate(
            evidence(brokerVisible = true, brokerCompleted = false)
        )
        assertEquals(EndXrExternalRuntimeReadinessVerdict.DEGRADED, result.verdict)
        assertTrue(result.warnings.any { it.contains("query did not complete") })
        assertFalse(result.warnings.any { it.contains("provider is not visible") })
    }

    @Test
    fun wrongSelectedRuntimeBlocks() {
        val result = EndXrExternalRuntimeReadiness.evaluate(
            evidence(selectedPackage = "org.other.runtime")
        )
        assertEquals(EndXrExternalRuntimeReadinessVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockers.any { it.contains("different runtime package") })
    }

    @Test
    fun failedNativeProbeIsDegradedWhenMetadataAndSelectionAreGood() {
        val result = EndXrExternalRuntimeReadiness.evaluate(
            evidence(probePass = false, probeFail = true)
        )
        assertEquals(EndXrExternalRuntimeReadinessVerdict.DEGRADED, result.verdict)
        assertTrue(result.warnings.any { it.contains("self-test failed") })
    }

    @Test
    fun missingRuntimeLibraryBlocks() {
        val result = EndXrExternalRuntimeReadiness.evaluate(evidence(libraryExists = false))
        assertEquals(EndXrExternalRuntimeReadinessVerdict.BLOCKED, result.verdict)
        assertTrue(result.blockers.any { it.contains("native library is missing") })
    }
}
