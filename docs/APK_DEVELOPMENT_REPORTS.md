# APK development reports

After the static APK analysis finishes, each imported APK card offers three clipboard outputs. Each output is generated from that card's actual `ImportedApkInfo`, `ApkRuntimeAssessment`, EndXR capability registry and the current device/install snapshot.

## 1. AI task prompt

The prompt is ready to paste into ChatGPT, Claude or another coding assistant. It includes the selected package and version, device/API/ABI, analyzer findings with evidence and next steps, native library and OpenXR markers, and the current EndXR capability inventory.

The prompt asks the coding assistant to inspect the current `main` branch, reuse existing runtime/input contracts, trace actual API calls, implement one cohesive patch, add tests and state exactly what remains unverified. It explicitly warns against duplicating already implemented OpenXR Actions/IPC, claiming third-party runtime compatibility from Sandbox tests, fabricating controller features or bypassing signature-protected vendor permissions.

## 2. Technical report

The technical report is intended for an engineer. It lists package identity, versions, device API and ABIs, declared permissions, features, activities, services, providers, native libraries, engine/XR route, graphics markers, OpenXR extension and entry-point strings, vendor references, structured compatibility findings, and EndXR's current capabilities.

Permission declarations and permission grants are kept separate. A tracked runtime permission is reported as granted or denied only when the exact imported version is installed; otherwise its current grant state is shown as unknown. Other declared permissions are not assigned a guessed runtime state.

## 3. Full report

The full report includes the technical report plus all manifest collections retained by the importer, VR metadata key/value pairs, detailed findings, the complete current EndXR runtime capability inventory and the known verification boundaries.

## Evidence and interpretation

All three outputs retain the distinction between static evidence and runtime verification:

- A native string is a search hint, not proof that the game executes the corresponding API.
- A manifest permission is not proof that the game requested it or that Android granted it.
- A missing Android `uses-feature` is not, by itself, proof that sideload installation is blocked.
- An OpenXR extension string does not establish that the extension is required; the runtime must observe the actual `xrCreateInstance` request.
- `VERIFIED_IN_PROCESS` means bundled tests/Sandbox only. It is not a claim that RuntimeBroker selection or an unrelated game process works.
- Partial hand tracking is not promoted to real tracked 6DoF controller input.

The generator is deterministic and pure Kotlin, with unit tests for report contents and unknown/denied permission states.
