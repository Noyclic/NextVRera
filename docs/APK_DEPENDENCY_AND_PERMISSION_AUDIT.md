# APK dependency and permission audit

NextVRera statically inspects APK archives and Android package metadata without executing game code. A detected string is evidence for investigation, not proof that a code path runs or an API is mandatory.

## What the report inventories

The library card and copied report include:

- Android manifest permissions, required `uses-feature` declarations, Activities, Services and Providers.
- Native libraries, ABI, engine markers, OpenXR entry-point and extension strings, and graphics API markers.
- Meta/Oculus/Quest references found in manifest permissions, Android components, metadata, native library names and bounded native-code string scans.
- A structured EndXR compatibility map with severity, evidence, practical impact and the next diagnostic step.

## How to read the EndXR compatibility map

The map distinguishes three classes of evidence:

- **Blocker** — a concrete device-level mismatch such as a minimum Android API above the device API or no matching native ABI. A native ABI mismatch means the packaged native code cannot normally be loaded on the device; it is not merely an XR feature warning.
- **Warning** — a possible compatibility gap such as an unadvertised OpenXR extension, Vulkan markers while EndXR exposes an OpenGL ES binding, incomplete hand/controller input, missing Android features, or vendor-specific Quest dependencies.
- **Information** — a finding that narrows the next test but does not establish either compatibility or incompatibility.

EndXR's OpenXR extension list is sourced from the runtime capability inventory and mirrors the names currently advertised by the native runtime:

- `XR_KHR_android_create_instance` — verified by bundled runtime tests / in-process Sandbox, but external RuntimeBroker selection by a separate game process remains unverified.
- `XR_KHR_opengl_es_enable` — GLES session and swapchain path are verified in Sandbox; a third-party title still needs a real launch test.
- `XR_EXT_hand_tracking` and `XR_EXT_hand_interaction` — partial. The camera provides estimated hand joints and pinch/grasp/aim input; these are not fully tracked 6DoF controller poses. Real game input, physical controller buttons/sticks and haptics are not confirmed.

The APK scanner compares statically discovered `XR_*` strings with this inventory. An unadvertised extension is a risk only if the game actually requests it and requires it. A string may come from an unused engine plugin or optional code path. An absent string can also be hidden by stripped symbols, dynamic construction or split APK packaging.

Vulkan markers are treated similarly. EndXR currently exposes OpenGL ES, not a Vulkan graphics binding. A Vulkan marker can indicate an alternative renderer bundled with the game; it does not prove that Vulkan is selected. A Vulkan-only title would need Vulkan support in EndXR or a GLES build of the game.

## Quest / Meta / Oculus dependencies

The analyzer checks vendor references in package IDs, manifest permissions, Android Services, Activities, Providers, metadata, native library names and bounded native-code strings. This catches APKs that declare a Quest service or vendor permission without bundling a library whose filename makes the dependency obvious.

A Quest APK can contain both the Khronos OpenXR Loader and proprietary Meta/Oculus services. In that case the report keeps the OpenXR preflight route visible, while separately warning that EndXR does not automatically replace private Meta Runtime APIs, Quest system services or signature-protected permissions. The required path depends on what the game actually calls.

A vendor permission may be signature- or privileged-protected. NextVRera cannot grant it through an ordinary Android permission dialog. The game also must request normal Android runtime permissions itself; the launcher cannot request them on behalf of another package.

## Missing Android features are not automatically a sideload blocker

Android's `<uses-feature>` declaration describes device capabilities an app uses or requires. The Android documentation says the system itself does not check feature matches before installing an app; services such as Google Play can use these declarations for device filtering. Therefore, a missing feature appears as a warning, not as proof that a locally imported APK cannot be installed. The app may still fail, disable functionality or crash when it uses a missing capability.

The minimum Android API and native ABI checks remain separate and stronger blockers for the current phone. Other installation and runtime restrictions can still apply.

## Why a permission dialog may not appear

Android runtime permission dialogs belong to the target app. NextVRera cannot call `requestPermissions()` on behalf of another package. The game must declare the permission and request it while running.

For installed packages, NextVRera checks current grants for common dangerous Android permissions, including camera, microphone, location, sensors, Bluetooth, notifications and media. If one is not granted, the card links to Android App info so the user can review permissions.

A missing dialog can mean the game never requested it, the permission was denied previously or is no longer askable, Android/device policy restricts it, or the permission is not a normal runtime permission.

The settings shortcut is diagnostic/remediation only. It never grants access automatically.

## Limits

- Static strings may be unused, stripped, dynamically resolved or hidden behind engine plugins.
- A listed permission does not prove the game requests it at runtime.
- A single base APK may omit declarations or libraries supplied by split APKs.
- A successful Android launch does not establish RuntimeBroker selection, OpenXR session creation, input compatibility or rendered game frames.
- EndXR Sandbox passing its own probe does not yet prove that an unrelated Quest APK can use EndXR in a separate process.


## Copy-ready development reports

After analysis, the APK card provides three clipboard actions: an AI implementation prompt, a technical report, and a full report. They are produced from the current APK's metadata/audit findings and the centralized EndXR capability inventory. See [APK development reports](APK_DEVELOPMENT_REPORTS.md) for the fields and interpretation rules.
