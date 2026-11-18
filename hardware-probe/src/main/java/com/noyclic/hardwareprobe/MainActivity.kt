package com.noyclic.hardwareprobe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.FileInputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button
    private lateinit var exportButton: Button
    private lateinit var scrollView: ScrollView
    private val report = StringBuilder()
    private var lastGraphicsError: String? = null

    private external fun probeNativeLibraries(): String
    private external fun probeNativeRuntimeEnvironment(nativeLibraryDir: String, skelLibraryDir: String): String
    private external fun snapshotLoadedQnnModules(skelLibraryDir: String): String

    private val qnnDiagnostics by lazy {
        QnnDeepDiagnostics(
            this,
            { nativeDir, skelDir -> probeNativeRuntimeEnvironment(nativeDir, skelDir) },
            { skelDir -> snapshotLoadedQnnModules(skelDir) }
        )
    }

    private data class GraphicsInfo(
        val renderer: String,
        val openGlesVersion: String,
        val shadingLanguageVersion: String,
        val eglVersion: String,
        val fp16Extensions: List<String>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            System.loadLibrary("hardwareprobe")
        } catch (error: Throwable) {
            append("Native probe library: LOAD FAILED — " + error.message)
        }
        buildUi()
        appendDeviceReport()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
        }
        val title = TextView(this).apply {
            text = "NextVRera Hardware Probe"
            textSize = 23f
            setTextColor(0xff172033.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "CPU · Adreno GPU · Qualcomm QNN / Hexagon"
            textSize = 13f
            setTextColor(0xff526078.toInt())
            setPadding(0, 4, 0, 14)
        }
        runButton = Button(this).apply {
            text = "RUN ALL HARDWARE TESTS"
            setOnClickListener { runAllTests() }
        }
        val copyButton = Button(this).apply {
            text = "COPY DIAGNOSTIC REPORT (SHORT REPORTS ONLY)"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("NextVRera hardware probe", report.toString())
                )
                append("Report copied to clipboard. For full QNN logs, use EXPORT FULL REPORT.")
            }
        }
        exportButton = Button(this).apply {
            text = "EXPORT FULL REPORT (.TXT)"
            setOnClickListener { exportFullReport() }
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xff1d2939.toInt())
            setTextIsSelectable(true)
            text = "Collecting device information…"
        }
        scrollView = ScrollView(this).apply { addView(output) }
        root.addView(title)
        root.addView(subtitle)
        root.addView(runButton)
        root.addView(copyButton)
        root.addView(exportButton)
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun exportFullReport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "NextVRera-QNN-diagnostics.txt")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_REPORT)
        } catch (error: Throwable) {
            append("Report export failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_REPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: run {
            append("Report export cancelled: no destination selected.")
            return
        }
        try {
            val snapshot = report.toString()
            contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer -> writer.write(snapshot) }
            } ?: error("Android could not open the selected destination")
            append("FULL REPORT EXPORTED: $uri")
            append("Attach the saved .txt file to your ChatGPT message; no clipboard required.")
        } catch (error: Throwable) {
            append("Report export failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun appendDeviceReport() {
        append("=== DEVICE ===")
        append("Manufacturer: " + Build.MANUFACTURER)
        append("Model: " + Build.MODEL)
        append("Device: " + Build.DEVICE)
        append("Board: " + Build.BOARD)
        append("Hardware: " + Build.HARDWARE)
        append("SoC model: " + if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "API<31")
        append("SoC manufacturer: " + if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "API<31")
        append("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        append("ABI: " + Build.SUPPORTED_ABIS.joinToString())
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        append("RAM total: " + memory.totalMem / (1024L * 1024L) + " MB")
        append("RAM available: " + memory.availMem / (1024L * 1024L) + " MB")
        append("Thermal status at launch: " + thermalStatus())
        append("")
        append("=== GRAPHICS ===")
        val graphics = queryGraphicsInfo()
        if (graphics == null) {
            append("OpenGL ES query: FAILED — " + (lastGraphicsError ?: "unknown EGL/GLES error"))
        } else {
            append("OpenGL renderer: " + graphics.renderer)
            append("OpenGL ES version: " + graphics.openGlesVersion)
            append("GLSL version: " + graphics.shadingLanguageVersion)
            append("EGL version: " + graphics.eglVersion)
            append("FP16-related GL extensions: " + graphics.fp16Extensions.joinToString().ifBlank { "none reported" })
        }
        val vulkanFeature = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == "android.hardware.vulkan.version" }
        append("Vulkan feature: " + (vulkanFeature != null))
        append("Vulkan feature version code: " + (vulkanFeature?.version?.toString() ?: "not advertised"))
        append("")
        append("=== NATIVE LIBRARY VISIBILITY ===")
        append(runCatching { probeNativeLibraries() }.getOrElse {
            "Native probe failed: " + it.javaClass.simpleName + ": " + it.message
        })
        append(inspectDspSkeleton())
        append("")
        append("=== QNN LOW-LEVEL ENVIRONMENT (PRE-RUN) ===")
        append(qnnDiagnostics.nativeEnvironmentReport())
        append("")
        append("=== PROBE BUILD ===")
        append("LiteRT runtime: 2.16.1")
        append("LiteRT GPU API: 2.16.1")
        append("Qualcomm QNN runtime/delegate: 2.34.0")
        append("Android declares libcdsprpc.so as optional native library.")
        append("Note: library visibility is not proof that a backend can execute a neural network.")
    }

    private fun queryGraphicsInfo(): GraphicsInfo? {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val eglVersions = IntArray(2)
            check(EGL14.eglInitialize(display, eglVersions, 0, eglVersions, 1)) {
                "eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError())
            }

            // EGL_OPENGL_ES3_BIT_KHR is 0x0040; retain the ES2 bit as a config fallback.
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040 or EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val configCount = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0)) {
                "eglChooseConfig failed: 0x" + Integer.toHexString(EGL14.eglGetError())
            }
            check(configCount[0] > 0 && configs[0] != null) { "No GLES3-compatible EGL config" }

            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) {
                "Could not create GLES 3 context: 0x" + Integer.toHexString(EGL14.eglGetError())
            }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreatePbufferSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError())
            }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError())
            }

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
            val shadingVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ')
            val fp16Extensions = extensions
                .filter { it.contains("half_float", ignoreCase = true) || it.contains("fp16", ignoreCase = true) }
                .distinct()
                .sorted()

            return GraphicsInfo(
                renderer = renderer,
                openGlesVersion = glVersion,
                shadingLanguageVersion = shadingVersion,
                eglVersion = eglVersions[0].toString() + "." + eglVersions[1],
                fp16Extensions = fp16Extensions
            )
        } catch (error: Throwable) {
            lastGraphicsError = error.javaClass.simpleName + ": " + error.message
            return null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    private fun inspectDspSkeleton(): String {
        val name = "libQnnDspV66Skel.so"
        val file = File(applicationInfo.nativeLibraryDir, name)
        if (!file.isFile) return "$name=NOT_FOUND in nativeLibraryDir"

        return try {
            val header = ByteArray(20)
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count <= 0) break
                    offset += count
                }
                check(offset == header.size) { "ELF header is truncated" }
            }
            check(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                "file is not ELF"
            }
            val elfClass = header[4].toInt() and 0xff
            val machine = (header[18].toInt() and 0xff) or
                ((header[19].toInt() and 0xff) shl 8)
            val className = when (elfClass) {
                1 -> "ELF32"
                2 -> "ELF64"
                else -> "ELFCLASS_$elfClass"
            }
            val machineName = when (machine) {
                164 -> "QUALCOMM_DSP6 / Hexagon DSP"
                183 -> "AArch64"
                40 -> "ARM32"
                else -> "e_machine=$machine"
            }
            "$name=PRESENT ($className, $machineName, ${file.length()} bytes); DSP-side image, not an ARM dlopen target"
        } catch (error: Throwable) {
            "$name=PRESENT but ELF inspection failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "not available"
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        return when (manager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    private fun runAllTests() {
        runButton.isEnabled = false
        runButton.text = "TESTS RUNNING…"
        append("")
        append("=== INFERENCE BENCHMARKS ===")
        executor.execute {
            val modelBytes = runCatching { assets.open(MODEL_NAME).use { it.readBytes() } }
                .getOrElse {
                    publish("Model load failed: " + it.javaClass.simpleName + ": " + it.message)
                    finishTests()
                    return@execute
                }

            publish("Model: MobileNet V1 224 INT8 (" + modelBytes.size / 1024 + " KB)")
            publish(qnnDiagnostics.prepareSkeletons())
            publish("")
            publish("=== QNN LOW-LEVEL ENVIRONMENT (BEFORE DELEGATE) ===")
            publish(qnnDiagnostics.nativeEnvironmentReport())
            val thermalBefore = thermalStatus()
            publish("Thermal status before benchmarks: " + thermalBefore)
            if (thermalBefore in setOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")) {
                publish("WARNING: device is thermally throttled; benchmark numbers may be lower than cool-device performance.")
            }

            benchmark("CPU / XNNPACK — 1 thread", modelBytes) { options ->
                options.setNumThreads(1)
                null
            }
            benchmark("CPU / XNNPACK — 4 threads", modelBytes) { options ->
                options.setNumThreads(4)
                null
            }
            benchmark("CPU / XNNPACK — 8 threads", modelBytes) { options ->
                options.setNumThreads(8)
                null
            }
            benchmark("GPU / OpenGL delegate — compatibility-checked", modelBytes) { options ->
                val compatibility = CompatibilityList()
                val supported = compatibility.isDelegateSupportedOnThisDevice
                publish("GPU compatibility list: " + if (supported) "SUPPORTED" else "NOT SUPPORTED")
                if (!supported) {
                    throw UnsupportedOperationException(
                        "TensorFlow Lite GPU CompatibilityList rejects this device/configuration."
                    )
                }
                val gpuDelegate = GpuDelegate(compatibility.bestOptionsForThisDevice)
                options.addDelegate(gpuDelegate)
                gpuDelegate
            }
            benchmark("Qualcomm QNN / DSP backend", modelBytes) { options ->
                options.setUseNNAPI(false)
                options.setUseXNNPACK(false)
                val qnnOptions = QnnDelegate.Options()
                qnnOptions.setBackendType(QnnDelegate.Options.BackendType.DSP_BACKEND)
                publish(qnnDiagnostics.configureDelegate(qnnOptions))
                val qnnDelegate = QnnDelegate(qnnOptions)
                options.addDelegate(qnnDelegate)
                qnnDelegate
            }
            benchmark("Qualcomm QNN / HTP backend", modelBytes) { options ->
                options.setUseNNAPI(false)
                options.setUseXNNPACK(false)
                val qnnOptions = QnnDelegate.Options()
                qnnOptions.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                publish(qnnDiagnostics.configureDelegate(qnnOptions))
                val qnnDelegate = QnnDelegate(qnnOptions)
                options.addDelegate(qnnDelegate)
                qnnDelegate
            }

            publish("")
            publish("Thermal status after run: " + thermalStatus())
            publish("Tests finished. Export the report and send it back here.")
            finishTests()
        }
    }

    private fun benchmark(
        name: String,
        modelBytes: ByteArray,
        configure: (Interpreter.Options) -> Any?
    ) {
        publish("")
        publish("[" + name + "]")
        var interpreter: Interpreter? = null
        var delegateToClose: Any? = null
        var qnnLogCapture: QnnDeepDiagnostics.LogCapture? = null
        if (name.startsWith("Qualcomm QNN")) {
            qnnLogCapture = qnnDiagnostics.startLogCapture()
            publish("QNN native log capture: " + qnnLogCapture.start())
        }
        var stage = "preparing model"
        try {
            val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()

            val options = Interpreter.Options()
            stage = "configuring backend/delegate"
            delegateToClose = configure(options)

            stage = "creating interpreter and applying delegate"
            interpreter = Interpreter(model, options)

            stage = "allocating inference tensors"
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            val input = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
            for (i in 0 until input.capacity()) {
                input.put(i, ((i * 31 + 17) and 0xff).toByte())
            }

            stage = "warm-up inference"
            repeat(WARMUP_RUNS) {
                input.rewind()
                outputBuffer.clear()
                interpreter.run(input, outputBuffer)
            }

            stage = "measured inference"
            val samples = ArrayList<Double>(MEASURE_RUNS)
            repeat(MEASURE_RUNS) {
                input.rewind()
                outputBuffer.clear()
                val start = SystemClock.elapsedRealtimeNanos()
                interpreter.run(input, outputBuffer)
                samples += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }

            samples.sort()
            val median = samples[samples.size / 2]
            val p95 = samples[ceil(samples.size * 0.95).toInt().coerceIn(1, samples.size) - 1]
            publish("Result: PASS — interpreter completed actual model inference")
            publish(
                String.format(
                    Locale.US,
                    "Median: %.2f ms | P95: %.2f ms | approx %.1f inferences/s",
                    median,
                    p95,
                    1000.0 / median
                )
            )
            publish("Input tensor: " + inputTensor.shape().contentToString() + " / " + inputTensor.dataType())
            publish("Output tensor: " + outputTensor.shape().contentToString() + " / " + outputTensor.dataType())
            publish("Thermal status after backend: " + thermalStatus())
        } catch (error: Throwable) {
            publish("Result: FAIL — " + error.javaClass.simpleName + ": " + (error.message ?: "no message"))
            publish("Failure stage: " + stage)
            var cause: Throwable? = error.cause
            var depth = 1
            while (cause != null && depth <= 5) {
                publish("Caused by [" + depth + "]: " + cause.javaClass.name + ": " + (cause.message ?: "no message"))
                cause = cause.cause
                depth++
            }
            error.suppressed.take(3).forEach {
                publish("Suppressed: " + it.javaClass.name + ": " + (it.message ?: "no message"))
            }
            error.stackTrace.take(4).forEach {
                publish(" at " + it)
            }
        } finally {
            runCatching { interpreter?.close() }
            closeDelegate(delegateToClose)
            if (qnnLogCapture != null) {
                publish("--- QNN / FastRPC native logcat (this app PID) ---")
                qnnLogCapture.stop().forEach { publish(it) }
                publish("--- Loaded QNN / DSP / FastRPC modules after attempt ---")
                publish(qnnDiagnostics.loadedModulesReport())
            }
        }
    }

    private fun closeDelegate(delegate: Any?) {
        if (delegate == null || delegate === Unit) return
        runCatching {
            delegate.javaClass.methods
                .firstOrNull { it.name == "close" && it.parameterCount == 0 }
                ?.invoke(delegate)
        }
    }

    private fun publish(line: String) {
        runOnUiThread { append(line) }
    }

    private fun finishTests() {
        runOnUiThread {
            runButton.isEnabled = true
            runButton.text = "RUN ALL HARDWARE TESTS AGAIN"
        }
    }

    private fun append(line: String) {
        report.append(line).append('\n')
        if (::output.isInitialized) {
            output.text = report.toString()
            if (::scrollView.isInitialized) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_EXPORT_REPORT = 7104
        private const val MODEL_NAME = "mobilenet_v1_1.0_224_quant.tflite"
        private const val WARMUP_RUNS = 3
        private const val MEASURE_RUNS = 15
    }
}
