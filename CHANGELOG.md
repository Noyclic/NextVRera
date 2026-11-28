# Changelog

## 2026-11-28

### EndXR lightweight VIO sandbox (active prototype)
- Add a dedicated Sandbox lab with opt-in rear-camera capture and Economy/Balanced/Precision resolution and frame-rate caps.
- Downsample camera frames to a bounded 160×120 grayscale image before CPU processing.
- Add a grid-distributed FAST-like sparse corner detector, deterministic 64-bit BRIEF descriptors, local Hamming matching, and median/MAD motion consensus.
- Add quaternion/vector primitives and bounded midpoint IMU preintegration with timestamp-gap rejection and stationary gyro-bias estimation.
- Pair gyro measurements with linearly interpolated accelerometer values at each gyro timestamp; reject missing, stale, non-monotonic, and over-wide brackets instead of reusing the latest sensor values.
- Query the active Camera2 timestamp source through CameraX interop. Only allow camera-to-IMU orientation interpolation when the camera reports the REALTIME clock; UNKNOWN or unavailable sources keep visual and inertial diagnostics separate.
- Upgrade the aligned CameraX artifacts to stable 1.6.2 so the VIO lab can inspect the active camera's Camera2 timestamp-source metadata without relying on an unavailable older interop API.
- Keep a bounded timestamped orientation history and interpolate orientation at camera exposure timestamps before optional rotational image-flow compensation.
- Require an actual orientation-history bracket at the requested camera timestamp before returning a visual-inertial verdict; return the interpolated orientation for that timestamp and avoid duplicate history entries for equal timestamps.
- Count only successfully accepted, timestamp-paired IMU samples in diagnostics; never report visual-inertial fusion when camera/IMU clock comparability is unverified.
- Fuse rotation-vector orientation, gyroscope updates, accelerometer samples and sparse visual flow into a conservative relative pose report.
- Keep translation explicitly UNKNOWN/RELATIVE_ONLY; no metric metres or full 6DoF claim without calibrated depth/scale and device validation.
- Add JVM regression tests for geometry, sensor integration, sparse matching, translation scale honesty, invalid timestamps and pose reacquisition.
- Isolate the lab CameraX use case so it does not call unbindAll() against persistent hand-tracking camera sessions.

### EndXR lightweight VIO sandbox lab (prototype)
- Add a dedicated Sandbox screen to inspect rotation-vector and accelerometer event rates alongside rear-camera frame rate and image-gradient feature density.
- Add Economy (320×240), Balanced (480×360), and Precision (640×480) camera sampling presets for thermal/performance comparison.
- Keep the camera opt-in and rear-facing by default; the lab does not silently enable the front camera.
- Explicitly label feature-centroid movement as a diagnostic 2D proxy, not optical flow, metric translation, or a completed 6DoF tracker.
- This is instrumentation groundwork only; sparse feature matching, IMU preintegration, calibration, and validated 6DoF pose output remain future work.



### NextVRera / emulator smoke coverage
- Make the Sandbox tracking-provider selector smoke test mode-aware (AUTO/MANUAL), wait for actionable dialog content, and preserve a selector screenshot before assertions so UI failures are diagnosable.

### NextVRera / EndXR runtime readiness
### NextVRera / external OpenXR compatibility
- Added a conservative stage-by-stage assessment to the separate Khronos-loader client probe, covering service discovery, RuntimeBroker, loader, instance/system, stereo, GLES session, swapchain, frame/projection, hand tracking, input IPC and cleanup.
- Distinguish VERIFIED, PARTIAL, FAILED, BLOCKED, NOT_RUN and UNKNOWN; skipped or missing evidence is never promoted to success.
- Identify the first recorded failure in OpenXR call order and attach targeted next-step guidance.
- Add a structured JSON assessment export and one-tap copy/share actions to the external probe screen.
- Extend JVM regression tests for broker precedence, unavailable runtime, projection rejection, hand-tracking capability, input permission, missing evidence, JSON escaping and cleanup.
- Extend emulator instrumentation to validate and publish the structured report alongside raw diagnostics.
- Keep the last eight compact stage-only assessments in app-private storage and compare each run with the previous run to highlight verified-stage regressions and improvements; raw traces, camera data and native paths are not persisted.
- Add regression coverage for history ordering, bounded retention, status transitions, malformed storage and privacy boundaries.
- Correct the loader-validation documentation to describe swapchain-image FBO readback accurately; it is not compositor/display readback.
- Add an offline OpenXR result-code catalogue with stage-aware next steps, retry guidance and safe handling for unknown or vendor-specific errors.
- Generate a prioritized, read-only recovery plan: reconcile report contradictions first, then address the earliest failed prerequisite, then capture missing evidence. Lost sessions/instances are never retried with stale handles.
- Add per-stage field-presence coverage for all 15 diagnostic stages, including missing keys and NOT_APPLICABLE handling for skipped stages; explicitly do not convert field completeness into a compatibility score or VERIFIED verdict.
- Cross-check service and loader reports for contradictory instance/session/swapchain/frame/projection/hand-tracking/input evidence before export; retain original stage verdicts and surface integrity findings separately.
- Extend the JSON report with integrity findings and contextual first-failure diagnosis; preserve only compact stage snapshots in local history.
- Add JVM regression cases for result-code mapping, untrusted numeric values, duplicate log observations, prerequisite ordering, broker disagreement and cross-field contradictions.

- Added a conservative READY / DEGRADED / BLOCKED assessment combining runtime service metadata, the Khronos-selected RuntimeBroker provider, and the current native GLES/OpenXR self-test.
- Report the selected broker's provider/query failure distinctly instead of inferring provider visibility from a successful query string.
- Added regression coverage for missing broker, failed query, wrong selected runtime, missing native library and failed native probe.
- Exposed the readiness report in the Sandbox RuntimeBroker diagnostic and clipboard output; it does not claim arbitrary Quest APK compatibility.

## 2026-11-27

### NextVRera / EndXR game diagnostics
- Added a distinct, explicit consent step for detailed OpenXR runtime tracing; lifecycle telemetry and controller input no longer silently imply trace permission.
- Reset legacy combined trace approvals once on upgrade, preserving input/lifecycle grants while requiring fresh trace confirmation.
- Added a trace-only revoke action that preserves controller and lifecycle access.
- Included the current game's bounded low-level OpenXR trace journal in the post-launch diagnostic report, including first failure stage and last successful stage.
- Corrected the EndXR runtime documentation: the loader negotiates up to OpenXR 1.1.65, while full OpenXR 1.1 conformance and third-party game compatibility remain unverified.

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
