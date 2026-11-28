# NextVRera Logcat

A small, separate Android APK for capturing Android logcat through Shizuku.

## Use
1. Install and start Shizuku using Android Wireless debugging.
2. Open NextVRera Logcat and tap **Connect / grant Shizuku permission**.
3. Approve the Shizuku permission request.
4. Tap **Start capture**, reproduce the issue, then tap **Stop** and **Export TXT**.

The app runs only the read-only `logcat` command in a Shizuku user-service process. It does not require root, change system settings, or access other apps' private files. ADB/Shizuku permissions remain subject to Android and OEM restrictions. Native tombstones and protected buffers may still be unavailable.

The display filter only filters the on-screen preview; the exported TXT retains the captured stream. Captures are saved in app-private storage and capped at 8 MiB per session.
