# EndXR Tracking Providers

## Shared input contract

Every hand tracker terminates at `HandTrackingFrame`. Providers do not write OpenXR actions,
controller rows, gestures, or joints directly. The EndXR tracking input bridge selects one fresh
provider frame and sends it through the existing shared encoder:

```text
MediaPipe baseline / independent LiteRT pipeline / optional RTMPose refinement / GVXRS Dock
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

This keeps app-facing input stable when the tracking implementation changes. The existing
RTMPose path is deliberately a **hybrid refinement**, not a standalone tracker: MediaPipe owns
full-frame detection, hand association, handedness, world landmarks and pinch state; LiteRT
RTMPose refines only the 2D image landmarks for a successfully prepared hand crop. When a crop
is skipped or fails, its original MediaPipe points remain in the published hybrid frame.

The new **LiteRT standalone** provider is a separate pipeline. It must run its own full-frame
palm detector, build and track hand ROIs, run its own 21-point landmark model, calculate pinch
from its own observations, and publish a HandTrackingFrame without invoking MediaPipe. The
pure Kotlin LiteRtStandaloneTracker orchestration contract is now present and tested with fake
model adapters. The concrete LiteRT palm-detector adapter, model bundle installer, and
real-device inference path are still pending; therefore this provider deliberately remains
PLANNED and cannot be selected yet. The current RTMPose refinement switch is not a hidden
fallback for it.

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
- **LiteRT standalone** is the planned second full hand provider. It uses an independent full-frame palm detector followed by a top-down 21-point landmark model. The first landmark candidate remains RTMPose-Hand-LiteRT, but it must receive ROIs from the LiteRT palm detector, never from MediaPipe. The pipeline orchestration contract and tests are in LiteRtStandaloneTrackerPipeline.kt; concrete model adapters are still pending.
- **Accelerator policy** is per model: Qualcomm QNN HTP first when the delegate can be created and a real inference succeeds, then GPU, then CPU/XNNPACK. A backend is not reported as active merely because its library is present. LiteRtBackendPolicy captures this ordering; actual QNN/GPU/CPU delegate construction and runtime verification are not yet wired. Qualcomm's QNN delegate/runtime are distributed separately and must not be assumed to be available on every Snapdragon device.
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

1. Add the independent LiteRT palm-detector model store and its pinned model artifact.
2. Implement the LiteRT Interpreter/CompiledModel adapters for palm detection and RTMPose landmarks, with QNN -> GPU -> CPU fallback selected and measured independently per model.
3. Wire the standalone pipeline to its own CameraX frame consumer and publish only its own observations; do not start MediaPipe in LiteRT-only mode.
4. Validate two-hand acquisition, reacquisition, camera-to-input latency, dropped frames, thermal drift, RAM and battery on the physical Redmi Note 13 4G.
5. Keep the provider PLANNED until the APK build, model tensor tests, and physical-device tests pass.
6. Connect GVXRS Dock pairing, authenticated transport, time synchronization and calibrated landmark conversion.


Model acceptance must be based on the target device, not a benchmark from another SoC. For each
candidate, record cold-start and warm inference latency (p50/p95), end-to-end camera-to-landmark
latency, frame-loss rate, confidence stability during occlusion, RAM, thermal drift and battery
cost. Test CPU and each supported accelerator separately. Do not make the new adapter active by
default until its quality and sustained performance beat or complement the MediaPipe baseline.

No alternative tracker is claimed to be operational until its adapter is registered as READY and
tested on physical hardware.


## Independent LiteRT pipeline contract

\`\`\`text
CameraX frame
    |
LiteRT palm detector (full frame)
    |
up to two normalized palm ROIs
    |
LiteRT 21-point hand landmark model (per ROI)
    |
ROI continuity + presence checks + hand association
    |
shared pinch estimator
    |
HandTrackingFrame
    |
TrackingProviderManager -> EndXrHandInputEncoder
\`\`\`

LiteRtStandaloneTracker owns only ROI/track orchestration and the shared frame contract. Its
LiteRtPalmDetector and LiteRtHandLandmarkEstimator interfaces are deliberately free of
MediaPipe types. The landmark adapter must return full-frame normalized points after undoing
the crop transform; this keeps the EndXR encoder independent of model tensor layout. A failed
landmark inference removes that observation after a bounded miss count; it never copies a
MediaPipe frame into the LiteRT provider.

LiteRtBackendPolicy is a pure, testable priority contract. Each model may select a different
backend based on actual probe/inference results. The order is QNN HTP -> GPU -> CPU/XNNPACK.
The selected backend and every failed attempt must later be exposed in diagnostics, including
the reason for fallback. No QNN support or NPU execution is claimed by this policy alone.

## QNN DSP hand-landmark diagnostic screen

The Sandbox exposes a separate **LiteRT DSP — чёрный экран** action. It starts a CameraX
ImageAnalysis-only activity: no Preview surface is bound, and only the 21-point skeleton is
drawn over a black canvas. The diagnostic constructs the TensorFlow Lite Interpreter with
Qualcomm QNN `DSP_BACKEND`, disables NNAPI and XNNPACK delegates, and does not configure a GPU delegate. The
Interpreter's built-in CPU kernels can still execute operators that QNN does not delegate;
operator coverage must therefore be checked separately. Hexagon DSP skeleton ELF files are extracted from the APK into app-private storage
and passed to QNN with `setSkelLibraryDir`.

The first diagnostic model is the existing RTMPose-Hand FP16 LiteRT model. It is a top-down
single-hand landmark model: it does not find palms in a full frame and has no presence or
handedness output. The screen therefore starts with a center ROI and updates that ROI from
the previous 21 landmarks. A successful QNN inference is reported separately from complete
operator placement; operator coverage remains **NOT VERIFIED** until QNN telemetry confirms
the whole graph was delegated. Unsupported operations may still execute on default CPU kernels. This diagnostic does not use MediaPipe as a detector or fallback.

This diagnostic now runs two independent top-down ROIs and invokes the same QNN DSP-backed
RTMPose interpreter twice per camera frame. The seed ROIs cover the left and right halves of
the upright frame; after a plausible 21-point shape is produced, each ROI follows its own
landmarks and is reset to its seed after repeated misses. A geometry heuristic rejects collapsed
argmax layouts, and close duplicate wrist observations are suppressed. This is not a full-frame
palm detector: both hands need to enter their respective initial zones, and hands that overlap
closely may be represented as one. The UI reports the combined cost of both model invocations.
