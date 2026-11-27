# Changelog

## 2026-11-27

### NextVRera tracking / Qualcomm LiteRT
- Added a pure Kotlin Qualcomm MediaPipe-Hand palm-output decoder with anchor-relative box/keypoint decoding, clipped sigmoid scores, confidence filtering and non-maximum suppression.
- Bundled the exact 2,944-row `anchors_palm.npy` from the recipe's pinned MediaPipePyTorch revision and added a validating NumPy parser with a one-time model-store load API.
- Added the Qualcomm full-frame RGB preprocessor: normalized float [0,1], bilinear resize, floor-sized centered letterbox padding, NHWC/NCHW layouts and right-angle camera rotation.
- Added tests for real bundled anchors, malformed NumPy metadata, preprocessing geometry, letterbox mapping, tensor ranks, dynamic anchors, NMS and palm ROI geometry.
- Kept `LITERT_STANDALONE` PLANNED: LiteRT model adapters, QNN DSP/HTP execution, landmark ROI inference, service registration and physical-device validation remain outstanding.

# Changelog

## 2026-11-24

### NextVRera tracking / Sandbox
- Added an always-visible Sandbox action to copy a complete tracking-health report, including service/provider state, monotonic freshness counters, camera policy and latency fields.
- Surfaced the current tracking health verdict in the live Sandbox status without enabling the front camera or changing the user camera switch.
- Kept local Sandbox and persistent EndXR service diagnostics on one stable key/value contract for easier Redmi device debugging.


## 2026-11-21

### NextVRera tracking / EndXR
- Added a copy-ready tracking health diagnostic formatter with explicit IDLE/HEALTHY/DEGRADED/STALE/RECOVERED verdicts.
- Kept the diagnostic contract monotonic and evidence-based: it reports accepted frames, gaps, stale transitions, recoveries and clear timestamps without fabricating poses.
- Added regression coverage for grace, stale and reacquisition verdicts so Sandbox/EndXR reports can consume one stable health contract.


## 2026-11-19

### NextVRera tracking / EndXR
- Added a monotonic tracking-health state machine for accepted frames, short inference gaps,
  stale input and recovery.
- Connected freshness checks to the persistent camera service watchdog.
- Stale hand/controller input is cleared once after the 450 ms freshness window; the service
  checks this state every 100 ms without treating an empty hand observation as lost tracking.
- Exposed health state, accepted-frame count, gaps, stale transitions and recoveries in diagnostics.
- Made input clearing deterministic in JVM tests while retaining Android elapsed-realtime timestamps
  on device.
- Kept camera selection policy unchanged: the front camera remains opt-in and is never enabled
  automatically.

