# APK dependency and permission audit

NextVRera statically inspects APKs without executing them. Markers are triage evidence, not proof that code paths are called or permissions are mandatory.

## Inventory

The game card and full report expose every manifest permission, required `uses-feature`, declared activity, service and content provider, in addition to ABI, native libraries, XR extension strings and graphics markers.

## Why a permission dialog may not appear

Android runtime permission dialogs belong to the target app. NextVRera cannot call `requestPermissions()` on behalf of another package. The game must declare the permission and request it while running.

For installed packages, NextVRera checks current grants for common dangerous Android permissions, including camera, microphone, location, sensors, Bluetooth, notifications and media. If one is not granted, the card links to Android App info so the user can review permissions.

A missing dialog can mean the game never requested it, the permission was denied previously or is no longer askable, Android/device policy restricts it, or the permission is not a normal runtime permission.

Vendor namespaces such as `com.oculus.permission.*`, `com.meta.permission.*` and Samsung Gear VR permissions are listed separately. Signature- or privileged-level permissions cannot be granted by an ordinary permission dialog or NextVRera settings; they require the trusted vendor/system component or a supported integration.

The settings shortcut is diagnostic/remediation only. It never grants access automatically.

## Limits

- Static strings may be unused, stripped, dynamically resolved or hidden behind engine plugins.
- A listed permission does not prove the game requests it at runtime.
- A single base APK may omit declarations or libraries supplied by split APKs.
- A successful Android launch does not establish XR runtime compatibility.
