package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrInputAccessPolicyTest {
    private val fingerprint = "0123456789abcdef".repeat(4)

    @Test
    fun packageNameAndSignerFingerprintMustBothBeValid() {
        assertTrue(EndXrInputGrantCodec.isValidPackageName("com.example.vrgame"))
        assertTrue(EndXrInputGrantCodec.isValidPackageName("org.example_game.client"))
        assertTrue(EndXrInputGrantCodec.isValidPackageName("com.example"))
        assertFalse(EndXrInputGrantCodec.isValidPackageName("com..example"))
        assertFalse(EndXrInputGrantCodec.isValidPackageName("com.example;other"))
        assertTrue(EndXrInputGrantCodec.isValidFingerprint(fingerprint))
        assertFalse(EndXrInputGrantCodec.isValidFingerprint(fingerprint.dropLast(1)))
        assertFalse(EndXrInputGrantCodec.isValidFingerprint(fingerprint.uppercase()))
    }

    @Test
    fun encodingAndDecodingKeepOneApprovalPerPackage() {
        val first = EndXrInputGrantRecord("com.example.vrgame", fingerprint)
        val second = EndXrInputGrantRecord("org.example.client", "abcdef12".repeat(8))

        val encoded = EndXrInputGrantCodec.encode(listOf(first, first, second))
        val decoded = EndXrInputGrantCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertTrue(decoded.contains(first))
        assertTrue(decoded.contains(second))
    }

    @Test
    fun restoredGrantRequiresExactPackageAndSigningCertificate() {
        val record = EndXrInputGrantRecord("com.example.vrgame", fingerprint)
        val matchingClient = EndXrInputClient(
            packageName = "com.example.vrgame",
            label = "VR Game",
            signingCertificateSha256 = fingerprint
        )
        val differentSigner = matchingClient.copy(
            signingCertificateSha256 = "abcdef12".repeat(8)
        )
        val differentPackage = matchingClient.copy(
            packageName = "com.example.other"
        )

        assertTrue(EndXrInputGrantCodec.matches(record, matchingClient))
        assertFalse(EndXrInputGrantCodec.matches(record, differentSigner))
        assertFalse(EndXrInputGrantCodec.matches(record, differentPackage))
    }

    @Test
    fun legacyConsentDoesNotSilentlyGrantRuntimeTelemetry() {
        val legacy = "com.example.vrgame|$fingerprint"
        val decoded = EndXrInputGrantCodec.decode(setOf(legacy)).single()

        assertEquals("com.example.vrgame", decoded.packageName)
        assertEquals(fingerprint, decoded.signingCertificateSha256)
        assertFalse(decoded.runtimeTelemetryGranted)
    }

    @Test
    fun explicitTelemetryConsentSurvivesEncodingAndDecoding() {
        val consent = EndXrInputGrantRecord(
            packageName = "com.example.vrgame",
            signingCertificateSha256 = fingerprint,
            runtimeTelemetryGranted = true
        )

        val encoded = EndXrInputGrantCodec.encode(listOf(consent))
        val decoded = EndXrInputGrantCodec.decode(encoded).single()

        assertTrue(decoded.runtimeTelemetryGranted)
        assertEquals(consent, decoded)
    }

    @Test
    fun oldLifecycleTelemetryConsentDoesNotGrantDetailedTrace() {
        val lifecycleOnly = "com.example.vrgame|$fingerprint|1"
        val decoded = EndXrInputGrantCodec.decode(setOf(lifecycleOnly)).single()

        assertTrue(decoded.runtimeTelemetryGranted)
        assertFalse(decoded.runtimeTraceGranted)
    }

    @Test
    fun detailedTraceConsentRequiresAndPersistsTheSeparateFlag() {
        val consent = EndXrInputGrantRecord(
            packageName = "com.example.vrgame",
            signingCertificateSha256 = fingerprint,
            runtimeTelemetryGranted = true,
            runtimeTraceGranted = true
        )

        val decoded = EndXrInputGrantCodec.decode(
            EndXrInputGrantCodec.encode(listOf(consent))
        ).single()

        assertTrue(decoded.runtimeTelemetryGranted)
        assertTrue(decoded.runtimeTraceGranted)
        assertEquals(consent, decoded)
    }

    @Test
    fun legacyTraceApprovalsAreClearedWithoutRemovingLifecycleConsent() {
        val legacyFullConsent = listOf(
            EndXrInputGrantRecord(
                "com.example.vrgame",
                fingerprint,
                runtimeTelemetryGranted = true,
                runtimeTraceGranted = true
            )
        )
        val migrated = EndXrInputGrantCodec.clearRuntimeTraceConsent(legacyFullConsent).single()
        assertTrue(migrated.runtimeTelemetryGranted)
        assertFalse(migrated.runtimeTraceGranted)
        assertEquals("com.example.vrgame", migrated.packageName)
    }

    @Test
    fun traceConsentCanBeEnabledOnlyForTheMatchingLifecycleGrant() {
        val client = EndXrInputClient(
            packageName = "com.example.vrgame",
            label = "VR Game",
            signingCertificateSha256 = fingerprint
        )
        val lifecycleOnly = listOf(
            EndXrInputGrantRecord(
                client.packageName,
                fingerprint,
                runtimeTelemetryGranted = true
            )
        )
        val enabled = EndXrInputGrantCodec.withRuntimeTraceConsent(
            lifecycleOnly,
            client,
            enabled = true
        ).single()
        assertTrue(enabled.runtimeTelemetryGranted)
        assertTrue(enabled.runtimeTraceGranted)

        val wrongSigner = client.copy(signingCertificateSha256 = "abcdef12".repeat(8))
        assertTrue(runCatching {
            EndXrInputGrantCodec.withRuntimeTraceConsent(lifecycleOnly, wrongSigner, enabled = true)
        }.isFailure)
        val inputOnly = listOf(EndXrInputGrantRecord(client.packageName, fingerprint))
        assertTrue(runCatching {
            EndXrInputGrantCodec.withRuntimeTraceConsent(inputOnly, client, enabled = true)
        }.isFailure)
    }

    @Test
    fun traceConsentCanBeRevokedWithoutRemovingInputOrLifecycleAccess() {
        val client = EndXrInputClient(
            packageName = "com.example.vrgame",
            label = "VR Game",
            signingCertificateSha256 = fingerprint
        )
        val fullConsent = listOf(
            EndXrInputGrantRecord(
                client.packageName,
                fingerprint,
                runtimeTelemetryGranted = true,
                runtimeTraceGranted = true
            )
        )
        val revoked = EndXrInputGrantCodec.withRuntimeTraceConsent(
            fullConsent,
            client,
            enabled = false
        ).single()
        assertTrue(EndXrInputGrantCodec.matches(revoked, client))
        assertTrue(revoked.runtimeTelemetryGranted)
        assertFalse(revoked.runtimeTraceGranted)
    }

    @Test
    fun malformedTelemetryConsentFlagIsIgnored() {
        val decoded = EndXrInputGrantCodec.decode(
            setOf("com.example.vrgame|$fingerprint|yes")
        )

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun malformedTraceConsentFlagIsIgnored() {
        val decoded = EndXrInputGrantCodec.decode(
            setOf("com.example.vrgame|$fingerprint|1|yes")
        )

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun malformedStoredEntriesAreIgnored() {
        val decoded = EndXrInputGrantCodec.decode(
            setOf(
                "com.example.vrgame|$fingerprint",
                "com.example.vrgame|not-a-fingerprint",
                "not-a-package|$fingerprint",
                "missing-separator",
                "|$fingerprint"
            )
        )

        assertEquals(
            listOf(EndXrInputGrantRecord("com.example.vrgame", fingerprint)),
            decoded
        )
    }
}
