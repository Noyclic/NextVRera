# NextVRera LiteRT DSP Probe

A separate diagnostic APK for the pinned Qualcomm MediaPipe hand-detection models.

## Scope
- Downloads `HandDetector.tflite` and `HandLandmarkDetector.tflite` only after an explicit button press.
- Verifies exact file size and SHA-256 before installation and before running.
- Uses TensorFlow Lite Interpreter 2.16.1 with QNN 2.34.0 `DSP_BACKEND`.
- Disables NNAPI and XNNPACK; it does not intentionally fall back to CPU/GPU.
- Reports tensor names, shapes, types, byte sizes, warm-up, median and P95 invocation latency.
- Provides a copyable diagnostic report.

## Important limits
This first probe invokes each model with a zero-filled input tensor. A successful invocation proves model loading and delegate-backed invocation did not throw; it does **not** prove useful palm detection, correct ROI preprocessing, landmark quality, or two-hand tracking. Operator placement remains `NOT_VERIFIED` until QNN instrumentation provides direct evidence. A successful delegate setup alone is not proof of full DSP placement.

No camera is opened. The probe does not alter MediaPipe, the main app, or EndXR runtime state. It does not activate the standalone tracker.

## Device test
1. Build/install `litert-dsp-probe-debug.apk` on a supported Qualcomm device.
2. Tap **Download models** and wait for both SHA-256 checks.
3. Tap **Run strict QNN DSP test**.
4. Copy the full report and share it with the NextVRera maintainers.

A failure is a valid diagnostic result. Preserve the full exception and backend logs; do not silently retry with another backend.
