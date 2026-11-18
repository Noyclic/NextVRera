package com.noyclic.hardwareprobe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import com.qualcomm.qti.QnnDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var runButton: Button
    private var report = StringBuilder()

    private external fun probeNativeLibraries(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            System.loadLibrary("hardwareprobe")
        } catch (error: Throwable) {
            append("Native probe library: LOAD FAILED — ${error.message}")
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
            text = "COPY DIAGNOSTIC REPORT"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NextVRera hardware probe", report.toString()))
                append("Report copied to clipboard.")
            }
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xff1d2939.toInt())
            setTextIsSelectable(true)
            text = "Collecting device information…"
        }
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(title)
        root.addView(subtitle)
        root.addView(runButton)
        root.addView(copyButton)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun appendDeviceReport() {
        append("=== DEVICE ===")
        append("Manufacturer: ${Build.MANUFACTURER}")
        append("Model: ${Build.MODEL}")
        append("Device: ${Build.DEVICE}")
        append("Board: ${Build.BOARD}")
        append("Hardware: ${Build.HARDWARE}")
        append("SoC model: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "API<31"}")
        append("SoC manufacturer: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "API<31"}")
        append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        append("RAM total: ${memory.totalMem / (1024L * 1024L)} MB")
        append("RAM available: ${memory.availMem / (1024L * 1024L)} MB")
        append("Thermal status: ${thermalStatus()}")
        append("")
        append("=== GRAPHICS ===")
        append("OpenGL renderer: ${queryGlInfo()}")
        append("OpenGL version: ${queryGlVersion()}")
        append("Vulkan feature: ${packageManager.systemAvailableFeatures.any { it.name == "android.hardware.vulkan.version" }}")
        append("")
        append("=== NATIVE LIBRARY VISIBILITY ===")
        append(runCatching { probeNativeLibraries() }.getOrElse { "Native probe failed: ${it.message}" })
        append("")
        append("Note: library visibility is not proof that a backend can execute a neural network.")
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

    private fun queryGlInfo(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 4,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        renderer
    }.getOrElse { "unavailable: ${it.message}" }

    private fun queryGlVersion(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return@runCatching "unavailable"
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return@runCatching "unavailable"
        val result = "EGL ${versions[0]}.${versions[1]}"
        EGL14.eglTerminate(display)
        result
    }.getOrElse { "unavailable: ${it.message}" }

    private fun runAllTests() {
        runButton.isEnabled = false
        runButton.text = "TESTS RUNNING…"
        append("")
        append("=== INFERENCE BENCHMARKS ===")
        executor.execute {
            val modelBytes = runCatching { assets.open(MODEL_NAME).use { it.readBytes() } }
                .getOrElse {
                    publish("Model load failed: ${it.message}")
                    finishTests()
                    return@execute
                }
            publish("Model: MobileNet V1 224 INT8 (${modelBytes.size / 1024} KB)")
            benchmark("CPU / XNNPACK — 1 thread", modelBytes) { options ->
                options.setNumThreads(1)
            }
            benchmark("CPU / XNNPACK — 4 threads", modelBytes) { options ->
                options.setNumThreads(4)
            }
            benchmark("CPU / XNNPACK — 8 threads", modelBytes) { options ->
                options.setNumThreads(8)
            }
            benchmark("GPU / OpenGL delegate — default precision", modelBytes) { options ->
                options.addDelegate(GpuDelegate())
            }
            benchmark("GPU / OpenGL delegate — FP16 allowed", modelBytes) { options ->
                val gpuOptions = GpuDelegate.Options()
                gpuOptions.setPrecisionLossAllowed(true)
                options.addDelegate(GpuDelegate(gpuOptions))
            }
            benchmark("Qualcomm QNN / DSP (Hexagon)", modelBytes) { options ->
                val qnnOptions = QnnDelegate.Options()
                qnnOptions.setBackendType(QnnDelegate.Options.BackendType.DSP_BACKEND)
                qnnOptions.setSkelLibraryDir(applicationInfo.nativeLibraryDir)
                options.addDelegate(QnnDelegate(qnnOptions))
            }
            benchmark("Qualcomm QNN / HTP", modelBytes) { options ->
                val qnnOptions = QnnDelegate.Options()
                qnnOptions.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                qnnOptions.setSkelLibraryDir(applicationInfo.nativeLibraryDir)
                options.addDelegate(QnnDelegate(qnnOptions))
            }
            publish("")
            publish("Thermal status after run: ${thermalStatus()}")
            publish("Tests finished. Export the report and send it back here.")
            finishTests()
        }
    }

    private fun benchmark(
        name: String,
        modelBytes: ByteArray,
        configure: (Interpreter.Options) -> Unit
    ) {
        publish("")
        publish("[$name]")
        var interpreter: Interpreter? = null
        try {
            val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()
            val options = Interpreter.Options()
            configure(options)
            interpreter = Interpreter(model, options)
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            val input = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
            for (i in 0 until input.capacity()) input.put(i, ((i * 31 + 17) and 0xff).toByte())
            repeat(WARMUP_RUNS) {
                input.rewind()
                outputBuffer.clear()
                interpreter.run(input, outputBuffer)
            }
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
            publish("Result: PASS — actual model inference completed")
            publish(String.format(Locale.US, "Median: %.2f ms | P95: %.2f ms | approx %.1f inferences/s", median, p95, 1000.0 / median))
            publish("Input tensor: ${inputTensor.shape().contentToString()} / ${inputTensor.dataType()}")
            publish("Output tensor: ${outputTensor.shape().contentToString()} / ${outputTensor.dataType()}")
        } catch (error: Throwable) {
            publish("Result: FAIL — ${error.javaClass.simpleName}: ${error.message ?: "no message"}")
            error.cause?.let { publish("Cause: ${it.javaClass.simpleName}: ${it.message}") }
        } finally {
            runCatching { interpreter?.close() }
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
        if (::output.isInitialized) output.text = report.toString()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val MODEL_NAME = "mobilenet_v1_1.0_224_quant.tflite"
        private const val WARMUP_RUNS = 3
        private const val MEASURE_RUNS = 15
    }
}
