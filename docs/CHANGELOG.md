# Changelog

## 2026-11-18

### Tracking / EndXR
- Added a shared `TrackingSessionWatchdog` for the provider-manager to EndXR hand-input path.
- Brief inference gaps now stay in a bounded grace window; prolonged loss clears stale controller/hand input exactly once.
- Reacquisition is reported as a distinct state so diagnostics and future UI can show recovery without duplicating provider-specific recovery logic.
- Added JVM regression coverage for grace, loss, one-shot clear, reacquisition, and reset behavior.

### Verification
- Code changes were prepared on `main`; real-device tracking and Android instrumentation remain unverified in this run.
