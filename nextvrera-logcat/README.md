# NextVRera Logcat

A small, standalone Android APK for capturing Android logcat through Shizuku. It does not modify the main NextVRera app or require root.

## Use

1. Install and start Shizuku using Android Wireless debugging.
2. Open **NextVRera Logcat** and tap **Connect / grant Shizuku permission**.
3. Approve the permission request in Shizuku.
4. Tap **Start capture** (the first successful connection starts capture automatically).
5. Reproduce the issue, then tap **Stop**.
6. Use **Copy log** to put the complete saved session on the clipboard, or **Export TXT** to save it as a file.

## What it captures

- Runs the read-only `logcat -b all -v threadtime -T 1` command in a dedicated Shizuku UserService under the shell identity.
- Captures all Android log buffers readable by the Shizuku shell identity. Android/OEM restrictions may hide protected buffers.
- The display filter only changes the on-screen preview. It does not filter or delete the saved log.
- The preview is kept bounded in memory; the complete saved session is capped at 8 MiB.
- When the service queue is overloaded, old queued lines are dropped to keep memory bounded and a diagnostic line is added to the output.
- Sessions are stored in app-private storage as `last-logcat.txt`; uninstalling the app removes this private file.

## Limits

Shizuku must be running and this app must be authorized. ADB shell access does not grant access to other apps' private data. Native tombstones, protected log buffers, and some vendor diagnostics may remain unavailable without additional system privileges. This app cannot guarantee capture of logs emitted before capture starts; it begins with the most recent log entry and then follows new entries.
