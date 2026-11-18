# NextVRera Hardware Probe

A standalone diagnostic APK for measuring real Android inference backends on physical devices.

## What it tests

- Device identity, ABI, memory and Android build.
- OpenGL renderer and EGL version.
- Native linker visibility for Qualcomm QNN / HTP, OpenCL and Vulkan libraries.
- Actual MobileNet V1 INT8 LiteRT inference on CPU (XNNPACK), GPU delegate and Qualcomm QNN HTP delegate.
- Warm-up plus repeated inference timings (median, P95 and approximate inferences/second).
- Backend failures are reported independently; one failure does not stop later tests.

## Build

From the repository root:

```bash
gradle --no-daemon :hardware-probe:assembleDebug
```

APK: `hardware-probe/build/outputs/apk/debug/hardware-probe-debug.apk`

The model is downloaded by Gradle from the public TensorFlow Lite model mirror on GitHub during `preBuild`: [MobileNet V1 INT8 model](https://github.com/tflite-soc/tensorflow-models/blob/master/mobilenet-v1/mobilenet_v1_1.0_224_quant.tflite).

## Physical-device test

Install the APK on an ARM64 Android device, open **NextVRera Hardware Probe**, and tap **RUN ALL HARDWARE TESTS**. Wait for CPU, GPU and QNN results, then tap **COPY DIAGNOSTIC REPORT** and send the report back.

Run once after a reboot and once after the phone has warmed up. Keep the phone unplugged for the thermal comparison if practical.

## Interpreting QNN results

- **QNN PASS** means the delegate actually created an interpreter and completed model inference.
- **QNN FAIL / unsupported backend** means the installed delegate/runtime rejected the HTP path. This is not evidence that the Hexagon DSP is physically absent.
- **QNN libraries NOT_LOADABLE** is a linker visibility result, not a definitive statement that no vendor DSP firmware exists.
- The probe deliberately does not claim HTP support based only on the SoC name or the presence of a library.
- The benchmark uses one common INT8 CNN model across providers. It is a backend capability test, not a final hand-tracking performance score.

The APK is arm64-v8a only and does not change the main NextVRera app or GVXRS Mobile runtime.
