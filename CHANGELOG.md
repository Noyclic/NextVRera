# Changelog

## 2026-11-27

### NextVRera / Qualcomm LiteRT HTP probe
- Added runtime tensor shape, shape-signature, element-count and quantization metadata to the isolated QNN HTP report.
- Added model-specific validation for Qualcomm palm-detector and 21-point hand-landmark component tensor contracts, including NCHW/NHWC input layouts and detector anchor-count alignment.
- Added a persistent error history dialog and full-report clipboard action to the standalone probe APK.
- Added JVM regression tests for valid/invalid tensor contracts and wired the probe module tests into Android CI.
- Kept LITERT_STANDALONE marked PLANNED: contract validation and HTP warm-up do not prove decoding, full operator placement or live hand tracking.

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
