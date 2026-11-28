# External OpenXR Loader validation

## What this module checks

`runtime-client-probe` is a separate Android application package
(`com.noyclic.xrclientprobe`). It is not loaded inside the QuestPhoneXR process.
It first checks Android service discovery and then calls the official Khronos
Android OpenXR Loader from that independent process.

The runtime currently negotiates OpenXR **1.0.0**. The diagnostic APK is built
with Khronos loader/header **1.1.65**, but it deliberately requests
`XR_MAKE_VERSION(1, 0, 0)` from the runtime. Do not replace this with
`XR_CURRENT_API_VERSION`: that macro follows the header version (1.1.65 here),
which EndXR correctly rejects with
`XR_ERROR_API_VERSION_UNSUPPORTED (-4)` until OpenXR 1.1 is implemented.

The probe records these stages:

1. `xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", ...)`
   resolves the Android loader initialization entry point.
2. `xrInitializeLoaderKHR` receives the current JavaVM and Activity/Context.
3. The loader attempts to enumerate runtime extension properties.
4. The loader attempts `xrCreateInstance` with
   `XR_KHR_android_create_instance`.
5. If an instance is created, the probe requests an HMD system, reads system
   properties, and checks for `XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO`.
6. If the runtime advertises `XR_KHR_opengl_es_enable`, returns a valid
   HMD system, and confirms `XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO`,
   the probe creates an independent EGL ES 3 pbuffer context, queries graphics
   requirements, and attempts `xrCreateSession` / `xrBeginSession`.
7. When `XR_EXT_hand_tracking` is advertised, it resolves the three hand-tracking
   entry points, creates left and right hand trackers in the external session,
   and calls `xrLocateHandJointsEXT` at the predicted frame time in a LOCAL
   reference space. The report records whether each hand is active, how many of
   the 26 joint positions/orientations are valid, and how many claim TRACKED.
   An inactive hand is expected when no fresh camera frame is being published;
   active camera-estimated joints must not claim TRACKED.
8. It enumerates the swapchain formats advertised by that session, prefers
   `GL_RGBA8` when available, and otherwise uses the runtime's first advertised
   format. It creates a 128x64 GLES swapchain for two 64x64 eye regions.
9. It renders a red left-eye region and blue right-eye region into a released
   swapchain image, waits/begins a frame, locates the two stereo views, and
   submits one `XrCompositionLayerProjection` with separate side-by-side image
   rectangles. Before releasing the swapchain image, it attaches an FBO to that
   image and reads one pixel from each eye region to verify the red/blue test
   pattern. This validates the application's swapchain image contents; it does
   not observe a physical display or prove that a compositor presented pixels
   to a viewer. The probe enumerates environment blend modes and prefers `OPAQUE` when
   supported; otherwise it uses the first mode advertised by the runtime.
10. It tears down the reference space, swapchain, session, EGL context and instance.
   If the HMD system or primary stereo view is unavailable, the GLES session
   stage is skipped rather than issuing calls with an invalid system ID. If no
   blend mode is reported, the frame lifecycle is skipped.

The native loader is supplied by the Khronos
`org.khronos.openxr:openxr_loader_for_android` AAR. The probe does not contain
a local imitation of the loader or RuntimeBroker.

## Reading the result

## Stage-by-stage compatibility assessment

The separate client probe now appends an **External OpenXR Compatibility
Assessment** before the raw service and loader reports. It classifies each
stage independently as `VERIFIED`, `PARTIAL`, `FAILED`, `BLOCKED`,
`NOT_RUN`, or `UNKNOWN`.

The assessment covers Android service/library discovery, RuntimeBroker
selection, official loader initialization, extension enumeration, instance
creation, HMD system discovery, primary stereo configuration, GLES binding,
session creation, swapchain creation, frame lifecycle, projection submission,
hand tracking, input IPC, and resource cleanup.

Important interpretation rules:

- A visible runtime service is not an active RuntimeBroker selection.
- A successful loader initialization is not a successful runtime instance.
- A created session is not proof of swapchain or projection compatibility.
- Projection submission and swapchain-image pixel verification are separate
  observations.
- An advertised hand-tracking extension is not proof of active hands. The
  report retains per-hand tracker and locate evidence and does not claim
  metric 6DoF tracking from camera-estimated joints.
- Missing fields remain `UNKNOWN`; prerequisites that were never reached are
  `NOT_RUN` or `BLOCKED`. The analyzer does not fill skipped stages with
  assumed success.
- The report identifies the first recorded OpenXR/native failure in call
  order and includes stage-specific next steps.

The probe screen provides **COPY REPORT** for the complete text output and
**SHARE JSON** for the structured assessment. It also compares the current run
with the previous run on that device. Only compact stage verdicts, the selected
runtime package, timestamps and the first failure key are retained in app-private
storage (eight snapshots maximum); raw traces, camera samples and native library
paths are not stored in this history. A transition from VERIFIED to any other
status is shown as a regression, and a transition to VERIFIED is shown as an
improvement. Other non-verified status changes are reported without assigning a
false quality ranking. The instrumentation test writes
the JSON report to the CI Downloads folder alongside the raw service and
loader reports. This makes it possible to compare reports from different
Android devices without scraping the UI.

The assessment is diagnostic guidance, not an OpenXR conformance score and
not a compatibility guarantee for arbitrary Quest APKs.


- `xrInitializeLoaderKHR call=XR_SUCCESS` means the official Android loader
  initialized in the separate client package.
- `loader_header_api_version=1.1.65` records the Khronos header revision used
  to build the probe; `application_requested_api_version=1.0.0` records the
  version intentionally requested from EndXR.
- `xrCreateInstance=XR_SUCCESS` means the loader found and loaded an active
  runtime and successfully negotiated an OpenXR 1.0 instance.
- `XR_ERROR_API_VERSION_UNSUPPORTED` is an API-version mismatch, not proof that
  RuntimeBroker is missing. `XR_ERROR_RUNTIME_UNAVAILABLE` specifically points
  to the absence of an active runtime from RuntimeBroker.
- `xrGetSystem(HMD)=XR_SUCCESS` means that runtime exposed an HMD system to
  this client process.
- `primary_stereo_supported=YES` means the runtime advertised the primary
  stereo view configuration.
- `external_xrCreateSession=XR_SUCCESS` means the runtime accepted a real
  EGL-bound session from the separate client process.
- `external_xrCreateHandTrackerEXT_left=XR_SUCCESS` and
  `external_xrCreateHandTrackerEXT_right=XR_SUCCESS` mean the external process
  created both hand trackers. The corresponding `external_xrLocateHandJointsEXT_*`
  lines report live state and validity for all 26 joints. `active=NO` is not an
  API failure; it means no fresh camera hand sample was available at that instant.
  `tracked=0` is intentional because the phone-camera estimates are not true
  tracked metric poses.
- `external_projection_layer_submission=PASS` means the separate client
  submitted a two-view projection layer and the runtime returned success from
  `xrEndFrame`.
- `external_projection_swapchain_image_verification=PASS` means the probe
  attached an FBO to the acquired swapchain image and read the expected red
  left-eye and blue right-eye pixels before releasing that image.
- Swapchain-image readback validates application rendering into the OpenXR
  image. It is not compositor readback and does not prove that pixels reach a
  physical viewer or that a game is compatible.

If instance creation returns `XR_ERROR_RUNTIME_UNAVAILABLE`, inspect the
RuntimeBroker report. Service visibility alone does not select an active
runtime. Android obtains the active runtime from either the official
installable Khronos RuntimeBroker content provider or a system RuntimeBroker
provided by the device vendor. The Khronos installable Broker is a separate
working-group application. It must not be bundled as a private copy inside
QuestPhoneXR or replaced with a provider claiming its authority.

For a phone / plug-in-viewer setup:

1. Install the official [OpenXR Runtime Broker from Google Play](https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker).
2. Return to QuestPhoneXR Sandbox and tap **Выбор OpenXR Runtime**.
3. In the Broker's runtime list, select **EndXR — OpenXR Runtime**. If more than one runtime is installed, the user selection determines which runtime becomes active.

The runtime service has its own explicit Android display label and icon, separate from the app's launcher identity (**QuestPhoneXR**). The broker diagnostic report records the service label and whether an icon resource is declared, so a missing identity can be distinguished from a runtime-loading problem.
4. Return to Sandbox. QuestPhoneXR automatically reruns its in-process loader diagnostic and refreshes the Broker/provider report.
5. Launch **EndXR External Client Probe** separately to verify that the official Khronos loader in a different APK can create an instance and proceed through system/session/swapchain/frame calls.

If the Broker is already installed, the Sandbox button explicitly opens the Khronos-owned `org.khronos.openxr.runtime_broker.MainActivity` settings screen (the Broker is not assumed to expose a launcher intent). If it is missing, the button opens the official Play Store listing and can copy the listing URL. A device without Google Play may require the official Khronos-signed APK from a supported market/source; do not use random repackaged Broker APKs.

The Android 15 CI emulator used by this repository does not include an installable or system RuntimeBroker. Its expected `XR_ERROR_RUNTIME_UNAVAILABLE` is therefore a broker-selection limitation, not proof that the EndXR library failed to load. Physical-device selection and independent client loading remain separate acceptance checks. The CI probe requests OpenXR 1.0.0 explicitly, so it no longer produces a false API-version failure solely because the bundled Khronos headers are newer than the runtime's supported API line.

A successful `xrCreateInstance` is a major interoperability checkpoint, but
it is not proof of game compatibility. The separate client now attempts a
real GLES projection-layer submission and reads back the compositor output on
its own pbuffer surface. This is stronger than a zero-layer frame, but it still
does not present to a headset/window surface. EndXR's Action API is still partial: the paired same-signed diagnostic client can read
a snapshot directly through the signature permission. QuestPhoneXR also offers
an explicit, package-scoped read-only URI grant for one installed launcher app
at a time. That grant covers only `/controller_state`, is tied to the target
package's signing certificate, is reconciled when QuestPhoneXR is opened, and
can be revoked. A game must still integrate with the EndXR input contract; the
permission does not make an unrelated APK compatible by itself. The current
hand poses are estimates, not tracked controller poses; haptics, 6DoF tracking
and Meta/Oculus-specific services remain incomplete. A real game may also
require Vulkan, additional extensions or vendor APIs.

## NextVRera game-launch preflight

NextVRera's installed-APK library has a **Запустить через NextVRera + EndXR** path for APKs classified as OpenXR candidates. Before launch it checks the Broker using the installed game's ABI (not merely the ABI of the NextVRera process), follows installable-first/system-fallback precedence, and requires EndXR to be the likely candidate. If it is not, it opens the Khronos installable Broker and rechecks after the user returns.

The managed path then asks for explicit package-scoped IPC approval: read-only input access to `controller_state` and a separate write-only runtime event URI. Both grants are bound to the selected package signing certificate. Legacy input-only approvals do not automatically receive telemetry write access; the app asks for the updated consent. NextVRera starts the saved camera foreground-service configuration when camera permission is available and waits for a fresh MediaPipe frame before requesting Android to launch the game. The ordinary **Открыть установленное приложение** action does not silently grant either access or claim EndXR interception.

This only prepares a launch and reduces known mismatches. NextVRera cannot force Android's Loader to use EndXR from an Activity launch flag. A successful Broker candidate/file check does not prove the runtime library can be loaded, and an accepted Android startActivity request does not prove a game called xrCreateInstance or created a session. Proprietary Meta/Oculus APIs, Vulkan-only builds, and additional extensions remain compatibility blockers unless implemented by EndXR.

After returning from the game, NextVRera reads the protected runtime event endpoint.
The native EndXR runtime sends `xrCreateInstance`, `xrCreateSession`,
`xrBeginSession`, `xrEndSession`, successful projection-layer composition
milestones, and teardown events through a background IPC worker. The UI clears
the current-session state on `xrEndSession` even when the OpenXR instance
remains alive. It reports instance,
session, and composited-frame evidence separately. Empty `xrEndFrame` calls
are not counted as visible game frames; projection-frame counts advance only
when EndXR successfully composites a projection layer. IPC failures are retried
with backoff and frame milestones are coalesced so lifecycle events are less
likely to be displaced.

These events are package-scoped diagnostic telemetry, not a cryptographically
attested statement from Android's Loader. Code running under the same
user-approved package UID could theoretically submit an event itself. Treat
the signal as evidence that the approved app process reached the EndXR event
path, not as proof that all app rendering or input works on a physical viewer.

## CI and physical-device test

The GitHub Actions workflow installs QuestPhoneXR and the separately signed
debug client APK on an Android 15 emulator. It saves the service report,
RuntimeBroker report, Khronos loader report, OpenXR Sandbox report, screenshots
and instrumentation results in the `QuestPhoneXR-CI-diagnostics` artifact.

The external client probe also queries QuestPhoneXR's read-only input snapshot
provider from its separate package. The provider enforces a signature-level
read permission, so install the main APK and probe APK from the **same CI
workflow run**; mixing debug artifacts from different runs can fail the
signature check. A passing query confirms the protected IPC endpoint is
accessible to the paired diagnostic package; it does not prove that input
continues to be published after Sandbox pauses. See [IPC security](IPC_SECURITY.md).

The emulator currently has no Android RuntimeBroker provider, so the
`xrCreateInstance` result may correctly report that no runtime is selected.
The CI assertion is that the official loader initializes and returns a
diagnostic result without crashing; it does not force an unavailable runtime
to appear.

For a phone test, install both artifacts, then use the Khronos Runtime Broker
to select **EndXR — OpenXR Runtime** as the active runtime before launching the
probe. The loader does not select a runtime itself; the broker owns that
choice. If Monado or another runtime is selected, the probe will test that
runtime instead of EndXR.

Install both artifacts:

- `QuestPhoneXR-debug-apk`
- `QuestPhoneXR-external-client-probe-apk`

Open **EndXR External Client Probe** and copy or photograph the complete
report. After selection, the key checkpoints are `xrCreateInstance`,
`external_xrCreateSession`, swapchain image operations and
`external_xrEndFrame`. The report must be interpreted alongside the broker state. A phone
that has no installable or system RuntimeBroker cannot prove runtime selection
just because Android can see EndXR's exported service.


## Report integrity and result-code diagnosis

The separate client probe cross-checks its service and loader evidence before it
exports the compatibility assessment. The integrity pass reports contradictions
without rewriting stage verdicts. Examples include an instance-create error
paired with `runtime_instance_created=YES`, a successful session after an
explicit instance failure, frame calls that contradict their prerequisites,
projection readback marked PASS while `xrEndFrame` failed, and hand-joint
location succeeding after tracker creation failed.

Integrity findings are classified as BLOCKING, HIGH, WARNING or INFO. A
different active package reported by the installable and system RuntimeBroker
providers is a warning rather than an automatic failure: the loader's selected
provider precedence still determines which runtime is actually used. Missing
evidence remains missing; it is not guessed or promoted to success.

The result-code catalogue uses the symbolic `XR_ERROR_*` token as its lookup
key. It preserves any numeric value exactly as logged and does not infer a
symbolic result from a number alone. Unknown vendor or future codes remain
unclassified with generic next steps rather than receiving a guessed meaning.
Contextual hints distinguish RuntimeBroker, graphics, swapchain, frame,
hand-tracking and input failures.

The JSON export includes `integrity_report` and
`first_failure_diagnosis`. The integrity report is derived from the current
probe's service and loader text; the history store continues to keep only
compact stage verdicts. Raw reports, camera frames, hand samples and native
library paths are not added to persistent history. The integrity check is a
diagnostic aid, not a cryptographic attestation or OpenXR conformance test.


The assessment also generates a read-only recovery plan. Its priority is
deliberate: report contradictions first, then the earliest known OpenXR
failure, then blocked or incomplete prerequisites. It does not execute calls,
change RuntimeBroker selection, grant permissions, or launch a game. The JSON
field `recovery_plan` includes the plan state, ordered actions, expected
evidence and retry policy. A lost session or instance is never retried with
stale handles; an empty report is classified as incomplete evidence, not as a
successful probe.
