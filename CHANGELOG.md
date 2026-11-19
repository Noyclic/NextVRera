# Changelog

## 2026-11-19

### NextVRera tracking / EndXR
- Added a monotonic tracking-health state machine for accepted frames, short inference gaps,
  stale input and recovery.
- Added regression coverage for grace-window behavior, one-shot stale transitions and
  reacquisition after a prolonged tracking loss.
- Kept camera selection policy unchanged: the front camera remains opt-in and is never enabled
  automatically.
