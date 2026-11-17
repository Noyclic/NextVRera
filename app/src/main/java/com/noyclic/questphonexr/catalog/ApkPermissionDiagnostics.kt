package com.noyclic.questphonexr.catalog

/** Static permission classification. It never attempts to grant permissions. */
internal object ApkPermissionDiagnostics {
    private val runtimePermissionNames = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.BODY_SENSORS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.CALL_PHONE",
        "android.permission.READ_PHONE_STATE",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS"
    )

    fun isVendorProtectedCandidate(permission: String): Boolean {
        val normalized = permission.lowercase()
        return normalized.contains("permission.") &&
            listOf("oculus", "meta", "quest", "samsung.android.hmt", "samsung.android.vr")
                .any(normalized::contains)
    }

    fun runtimePermissions(requested: Collection<String>): List<String> =
        requested.filter { it in runtimePermissionNames }.distinct().sorted()

    fun vendorPermissions(requested: Collection<String>): List<String> =
        requested.filter(::isVendorProtectedCandidate).distinct().sorted()
}
