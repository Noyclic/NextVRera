# NextVRera Hardware Probe

A standalone diagnostic APK for measuring real Android inference backends on physical devices.

## What it tests

- Device identity, ABI, memory and current thermal state.
- Actual OpenGL ES and GLSL versions, EGL version, renderer and FP16-related extensions.
- Vulkan feature advertisement.
- Native linker visibility for Qualcomm QNN / HTP, FastRPC, OpenCL and Vulkan libraries.
- Actual MobileNet V1 INT8 LiteRT inference using CPU/XNNPACK (1, 4 and 8 threads), GPU delegate, QNN DSP and QNN HTP.
- Warm-up and repeated inference timings (median, P95 and approximate inferences/second).
- Backend setup, interpreter creation and inference failures reported as separate stages, including nested exception causes.

## Build

From the repository root:

\`\`\`bash
gradle --no-daemon :hardware-probe:assembleDebug
\`\`\`

APK: \`hardware-probe/build/outputs/apk/debug/hardware-probe-debug.apk\`

The model is downloaded by Gradle from the public TensorFlow Lite model mirror on GitHub during \`preBuild\`: [MobileNet V1 INT8 model](https://github.com/tflite-soc/tensorflow-models/blob/master/mobilenet-v1/mobilenet_v1_1.0_224_quant.tflite).

## Physical-device test

Install the APK on an ARM64 Android device, open **NextVRera Hardware Probe**, and tap **RUN ALL HARDWARE TESTS**. Wait for all backends, then tap **COPY DIAGNOSTIC REPORT** and send the report back.

For comparable numbers, let the phone cool to a NONE/LIGHT thermal state, unplug it, close other heavy apps, and run the probe once after a reboot. The app reports thermal state before and after each backend; SEVERE or CRITICAL results are throttled and should not be compared with cool-device runs.

## Interpreting results

- **PASS** means interpreter creation and repeated actual model inference completed.
- **FAIL at delegate setup/interpreter creation** is not proof that the silicon lacks the accelerator; inspect the full cause chain and native-library scan.
- **GPU CompatibilityList NOT SUPPORTED** means the TensorFlow Lite GPU runtime rejects the device/configuration before inference.
- **QNN HTP/DSP FAIL** can be due to inaccessible FastRPC, backend/SoC support, model quantization or unsupported operators. It does not alone prove that no DSP hardware exists.
- A loadable library is only a linker result, not proof that a backend can execute a neural network.
- The benchmark uses one common INT8 CNN model across providers. It is a backend capability check, not a final hand-tracking performance score.

The APK is arm64-v8a only and does not change the main NextVRera app or GVXRS Mobile runtime.
