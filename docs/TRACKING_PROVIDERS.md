# EndXR Tracking Providers

## Shared input contract

Every hand tracker terminates at `HandTrackingFrame`. Providers do not write OpenXR actions,
controller rows, gestures, or joints directly. The EndXR tracking input bridge selects one fresh
provider frame and sends it through the existing shared encoder:

```text
MediaPipe baseline / MediaPipe + RTMPose hybrid / future GVXRS Dock adapter
                         |
                         v
                 HandTrackingFrame
                         |
                         v
              TrackingProviderManager
             (AUTO or MANUAL selection)
                         |
                         v
              EndXrHandInputEncoder
                         |
                         v
              EndXR input snapshot
```

This keeps app-facing input stable when the tracking implementation changes. The RTMPose
path is deliberately a **hybrid refinement**, not a second standalone tracker: MediaPipe owns
full-frame detection, hand association, handedness, world landmarks and pinch state; LiteRT
RTMPose refines only the 2D image landmarks for a successfully prepared hand crop. When a crop
is skipped or fails, its original MediaPipe points remain in the published hybrid frame.

## Provider state

| Provider | Current state | Notes |
|---|---|---|
| MediaPipe | READY | Existing CameraX + Hand Landmarker path; remains the default |
| MediaPipe + RTMPose refinement | EXPERIMENTAL after explicit opt-in and successful GPU initialization; otherwise PLANNED | MediaPipe detects and tracks hands; RTMPose refines each crop sequentially and preserves MediaPipe world pose/handedness |
| GVXRS Dock Station | PLANNED | Data packet contract exists; pairing, live transport, camera and fusion are not connected |

An auxiliary OpenCV motion detector may be useful later for camera/motion assistance, but it is not
treated as a hand-landmark provider until it can emit the shared ordered hand-landmark contract.

## Candidate technologies and correct roles

- **MediaPipe Hand Landmarker** remains the current working 21-landmark hand provider. Its task outputs image landmarks, hand-local world landmarks and handedness.
  <https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker>
- **LiteRT 2.x + a custom hand model** is the planned second full hand provider. The model can remain in `.tflite` format; new Android inference work should target the `CompiledModel` API, which is Google's recommended modern runtime for CPU/GPU/NPU acceleration. We will benchmark each accelerator and fall back explicitly; device support is not assumed from the chipset name.
  <https://developers.google.com/edge/litert/android>
- **First LiteRT model candidate — RTMPose-Hand-LiteRT.** It produces the 21 standard wrist/finger keypoints and is distributed under Apache-2.0. The published Android results report about 4 ms GPU inference on Pixel 8a for a 256×256 FP16 model (~28 MB), but the same page says the CPU XNNPACK path declined that graph. Its preprocessing is top-down: a centered single-hand crop is expected. Therefore EndXR still needs a reliable ROI/crop stage, and neither the Pixel timing nor GPU support can be assumed on Redmi Note 13 / Adreno 610.
  <https://huggingface.co/litert-community/RTMPose-Hand-LiteRT>
- **Small quantized model candidate — STMicro hand_landmarks.** The model card describes an INT8 TFLite model with RGB input and 21 keypoints under Apache-2.0. Its card gives a simplified `(x,y,confidence)` output description, but ST's `hand_spe` prediction implementation uses four output tensors (raw landmarks, normalized landmarks, hand side and hand-presence probability) and a model-specific postprocessor. Treat the actual model file's tensor names, shapes, quantization parameters and coordinate frame as authoritative; do not decode it as a flat 63-float tensor until that signature is verified. The published ~20.75 ms result is from an STM32N6 NPU, not an Android phone. It is a candidate to inspect and benchmark, not an established fast Android option.
  <https://huggingface.co/STMicroelectronics/hand_landmarks>
- **ML Kit Pose Detection** is an auxiliary body/wrist source, not a direct replacement for articulated hand tracking. Its 33-point body skeleton includes hand points, but it does not provide the 21 finger-joint landmarks expected by the hand-frame contract. The API is currently beta and can change.
  <https://developers.google.com/ml-kit/vision/pose-detection>
- **OpenCV** can assist with motion, foreground/ROI detection and reacquisition, but it is not a hand-landmark model by itself.
- **GVXRS Dock Station** can provide a second camera viewpoint. It still needs time-synchronized packets, camera calibration and a tested fusion model before its coordinates can be merged with the primary phone.

Android's NNAPI was deprecated in Android 15. New accelerator work should therefore not depend on NNAPI as the long-term path; LiteRT's current Android guidance centers on `CompiledModel`.
<https://developer.android.com/ndk/guides/neuralnetworks/migration-guide>

## LiteRT model installation and runtime

The selected artifact is RTMPose-Hand-LiteRT, pinned by byte size and SHA-256. It is downloaded only on explicit request into the app's private no-backup directory, written to a temporary file, verified, then atomically renamed. A failed or interrupted transfer never replaces an existing verified model.

The shared `HandRoiRgbPreprocessor` samples CameraX's raw ARGB pixel buffer at 0/90/180/270 degrees directly into the upright ROI, without allocating an intermediate rotated Bitmap. Its crop coordinates match MediaPipe's unmirrored, display-aligned landmark frame. The NCHW path applies RGB ImageNet normalization while writing the reusable input buffer.\n\nThe isolated Google Play services LiteRT runner:
- requests GPU support with the Java `LiteRtInitializationOptions.builder()` API for Play services 17.0.0;
- verifies GPU compatibility by attempting to compile the GPU-only model, without calling `Environment.getAvailableAccelerators()` (unsafe with some older Play services runtime modules);
- warms up the graph and records 30 inference-only samples; the Sandbox reports p50/p95 including output readback, not camera-to-hand latency or application FPS;
- creates the CompiledModel and reusable TensorBuffers once on one dedicated thread;
- uses a single reusable NCHW input scratch buffer with one-frame-in-flight leasing;
- includes output readback in inference latency because GPU execution is asynchronous;
- closes input/output buffers before the model and environment.

The `RtmposeHandRefinementCoordinator` is now invoked from the persistent camera service when the user explicitly enables RTMPose. The MediaPipe controller snapshots the same raw camera frame into a reusable ARGB buffer; the coordinator copies it before the analyzer releases its frame gate, then prepares one ROI at a time. Successful RTMPose results replace only the 2D image landmarks. MediaPipe handedness, world landmarks and pinch/controller state are preserved; failed, busy or incomplete crop inference keeps the original MediaPipe points. The hybrid provider becomes EXPERIMENTAL only after the runner has successfully compiled and warmed the GPU graph.

The model predicts one crop only and has no presence or handedness outputs; MediaPipe supplies the ROI and its neutral detector baseline. AUTO can compare the hybrid only while it is fresh and EXPERIMENTAL, but its quality baseline is not a calibrated accuracy score; manual selection is available for controlled testing. If GPU compilation fails or refinement is busy, MediaPipe remains active. We intentionally do not fall back to CPU for this FP16 graph because its published CPU/XNNPACK path declines the model.

## Selection rules

- AUTO considers only READY or EXPERIMENTAL providers that have submitted a fresh frame.
- AUTO scores measured frame quality (55%), source pipeline latency against a 200 ms budget
  (25%), sustained frame cadence against a 30 FPS target (15%), and a small configured
  provider preference (5%). Cadence is measured from receiver-side uptime using a rolling
  30-interval window, so a single fast inference does not outweigh a source that delivers
  fewer usable frames per second. A provider without enough accepted samples starts with a
  neutral cadence score until its rate can be measured.
- Each provider tracks accepted/rejected observations, most recent frame age, latest and average
  pipeline latency, rolling average frame interval, estimated FPS, latest quality score, and its
  most recent rejection reason. These values are exported in EndXR diagnostics for device-by-device comparisons.
- MediaPipe currently reports a neutral 0.5 quality baseline because its detection confidence is
  not yet carried into the shared frame. This is not a measured accuracy score. Future adapters
  should provide calibrated per-frame confidence where their model exposes it.
- A provider must emit finite ordered landmarks and non-negative pipeline timing. Incomplete
  landmark sets, invalid quality values, duplicate/out-of-order timestamps, and frames exceeding
  the provider's declared hand limit are rejected and counted.
- MANUAL accepts only an available provider and never silently substitutes another tracker.
- Frames older than 300 ms are not exposed. Providers use receiver-side uptime for freshness
  and frame-cadence measurement; sender timestamps remain source-local and must not be compared
  across devices without clock synchronization.
- Out-of-order and duplicate timestamps, invalid quality values, and frames exceeding the
  provider's declared hand limit are rejected.
- When a provider becomes unavailable its cached frame is discarded.

## Next integration work

1. Test the explicitly enabled hybrid on a physical Redmi Note 13: two-hand quality, camera-to-input latency, dropped crops, thermal drift, memory and battery.
2. Add a stable hand-specific ROI confidence from the MediaPipe detector rather than the current neutral 0.5 baseline.
3. Compare AUTO quality and latency scores against the original MediaPipe-only frame before changing provider preference.
4. Connect GVXRS Dock pairing, authenticated transport, time synchronization and calibrated landmark conversion.


Model acceptance must be based on the target device, not a benchmark from another SoC. For each
candidate, record cold-start and warm inference latency (p50/p95), end-to-end camera-to-landmark
latency, frame-loss rate, confidence stability during occlusion, RAM, thermal drift and battery
cost. Test CPU and each supported accelerator separately. Do not make the new adapter active by
default until its quality and sustained performance beat or complement the MediaPipe baseline.

No alternative tracker is claimed to be operational until its adapter is registered as READY and
tested on physical hardware.


## Independent LiteRT Standalone provider

LITERT_STANDALONE is separate from LITERT_CUSTOM, which remains the MediaPipe + RTMPose hybrid.
The standalone provider must use its own LiteRT palm detector and 21-point landmark adapters and
must never consume MediaPipe observations. LiteRtStandaloneTracker owns two-hand identity, ROI
refresh/reacquisition, pinch state and conversion to HandTrackingFrame. It remains PLANNED until
concrete model adapters, real backend probes and device validation are connected. QNN HTP is the primary candidate for Qualcomm hand-model inference (the published Qualcomm profile
uses HTP with FP16). QNN DSP is a distinct backend and is not treated as HTP. Both are probed
separately per model; strict HTP/DSP diagnostics never count another backend's success as a pass.


### Pinned standalone model assets

The initial standalone model pair is pinned to Qualcomm's immutable Hugging Face revision
5699e8b7740034d58235fe95ae61a1606ea448e2:
- HandDetector.tflite: 7,072,880 bytes, SHA-256
  7fed965f73757224de6c7f9251781b924f242e753b1246c708525b015c6dc371
- HandLandmarkDetector.tflite: 8,070,980 bytes, SHA-256
  628856ec43b60e828c86d9ffac562f076484d1763242060e4cf16be08d36fb29

Both are downloaded only after an explicit install request, stored in app-private no-backup storage,
and verified before use. The detector produces raw box/keypoint regressions plus confidence scores;
its anchor decode and NMS must be implemented. The landmark component produces hand-presence,
handedness and 21 three-dimensional landmark values. These are model candidates, not a claim of
verified QNN execution on Snapdragon 685. Qualcomm's current published HTP profile uses
LiteRT 1.4.2 / QAIRT 2.51 and FP16 HTP; the pinned older files must be checked against the
delegate/runtime combination actually shipped by NextVRera.


### Strict QNN HTP model inspection

`LiteRtStandaloneHtpModelProbe` opens one verified model at a time through the Qualcomm
QNN LiteRT Interpreter delegate using `HTP_BACKEND`. NNAPI and XNNPACK are explicitly disabled.
The probe reports the actual input/output tensor names, shapes, data types, warm-up time, median
and p95 inference time. It does not publish hand frames and does not substitute another backend.

A successful HTP delegate warm-up is not proof that every graph operator ran on HTP. The
`operatorPlacement` field therefore remains `NOT_VERIFIED` until the device reports QNN
partition/operator placement. The model tensor contracts are discovered at runtime rather
than assumed from a different export.

The Qualcomm AI Hub profile currently documents LiteRT 1.4.2 / QAIRT 2.51 / QNN delegate
2.51 with HTP FP16 for this model family. The current Android integration still uses the
2.34.0 QNN delegate as an initial compatibility probe; it must be updated only after the
2.51 runtime is checked against the APK dependency graph. Qualcomm's published hand-model
profiles do not list Snapdragon 685, so HTP support on the Redmi Note 13 4G remains unverified.


## Isolated Qualcomm HTP probe APK

The QNN Interpreter delegate is isolated in the separate `:litert-htp-probe` Android application.
The main NextVRera app uses Google Play services LiteRT 17.0.0 for its existing CompiledModel
GPU path. That runtime removed the legacy `org.tensorflow.lite.Delegate` APIs required by
Qualcomm's QNN Interpreter delegate. Mixing both runtimes in the same app variant is not valid.

The standalone probe APK bundles the legacy LiteRT Interpreter + Qualcomm QNN delegate without
Google Play services LiteRT. It downloads the same two immutable Qualcomm model assets, verifies
their SHA-256 hashes, disables NNAPI and XNNPACK, and performs strict HTP-only warm-up and
benchmark attempts. The probe APK is for hardware compatibility evidence only; it does not
activate or replace the production tracker. A successful warm-up still does not prove complete
operator placement on HTP.

The main app's MediaPipe + RTMPose GPU path remains unchanged while this isolated test runs.
