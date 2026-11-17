package com.noyclic.questphonexr.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPermissionDiagnosticsTest {
    @Test
    fun separatesAndroidRuntimePermissionsFromVendorPermissions() {
        val requested = listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "com.oculus.permission.HAND_TRACKING",
            "com.meta.permission.USE_SCENE"
        )
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            ApkPermissionDiagnostics.runtimePermissions(requested)
        )
        assertEquals(
            listOf("com.meta.permission.USE_SCENE", "com.oculus.permission.HAND_TRACKING"),
            ApkPermissionDiagnostics.vendorPermissions(requested)
        )
    }

    @Test
    fun doesNotClassifyOrdinaryAndroidPermissionsAsVendorPermissions() {
        assertTrue(ApkPermissionDiagnostics.vendorPermissions(
            listOf("android.permission.CAMERA", "android.permission.VIBRATE")
        ).isEmpty())
    }

    @Test
    fun recognizesOculusAndSamsungPermissionNamespaces() {
        assertTrue(ApkPermissionDiagnostics.isVendorProtectedCandidate(
            "com.oculus.permission.USE_ANCHORS"
        ))
        assertTrue(ApkPermissionDiagnostics.isVendorProtectedCandidate(
            "com.samsung.android.hmt.vrsvc.permission.READ"
        ))
        assertTrue(!ApkPermissionDiagnostics.isVendorProtectedCandidate(
            "android.permission.CAMERA"
        ))
    }
}
