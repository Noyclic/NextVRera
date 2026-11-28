package com.noyclic.questphonexr.sandbox

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/** First-stage VIO lab. Image centroid motion is diagnostic only, never metric 6DoF. */
class EndXrVioLabActivity : ComponentActivity(), SensorEventListener {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private lateinit var sensors: SensorManager
    private var rotation: Sensor? = null
    private var accelerometer: Sensor? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var report: TextView
    private var rotationEvents = 0L
    private var accelerationEvents = 0L
    private var cameraFrames = 0L
    private var lastCameraFrameNs = 0L
    private var cameraFps = 0f
    private var featureCount = 0
    private var featureMotionX = 0f
    private var featureMotionY = 0f
    private var accelerationMagnitude = 0f
    private var lastFeatureCenterX = Float.NaN
    private var lastFeatureCenterY = Float.NaN
    private var mode = Mode.BALANCED
    private var cameraMessage = "Камера выключена"

    private data class Metrics(\n        val rotationEvents: Long, val accelerationEvents: Long, val cameraFrames: Long,\n        val cameraFps: Float, val featureCount: Int, val motionX: Float, val motionY: Float,\n        val accelerationMagnitude: Float\n    )\n\n    private enum class Mode(val width: Int, val height: Int, val fpsLabel: String) {
        ECONOMY(320, 240, "5–8 FPS target"),
        BALANCED(480, 360, "10–15 FPS target"),
        PRECISION(640, 480, "20–30 FPS target")
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            cameraMessage = "Нет разрешения CAMERA"
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(12, 17, 27))
        }
        report = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(210, 220, 232))
            setPadding(0, 18, 0, 18)
        }
        content.addView(TextView(this).apply {
            text = "EndXR 6DoF Lab"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Камера + IMU без ARCore. Первый этап измеряет датчики и визуальные ориентиры. Метрическая 6DoF-поза пока не заявляется."
            setTextColor(Color.LTGRAY)
            textSize = 14f
        })
        content.addView(report)
        content.addView(button("Economy · 320×240") { setMode(Mode.ECONOMY) })
        content.addView(button("Balanced · 480×360") { setMode(Mode.BALANCED) })
        content.addView(button("Precision · 640×480") { setMode(Mode.PRECISION) })
        content.addView(button("Запустить / остановить камеру") {
            if (running.get()) stopCamera() else requestCamera()
        })
        content.addView(button("Сбросить измерения") { resetMeasurements() })
        content.addView(button("Назад в Sandbox") { finish() })
        setContentView(ScrollView(this).apply { addView(content) })
        refresh()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun setMode(value: Mode) {
        mode = value
        if (running.get()) {
            stopCamera()
            startCamera()
        }
        refresh()
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (!running.compareAndSet(false, true)) return
        cameraMessage = "Подключение задней камеры…"
        refresh()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || !running.get()) return@addListener
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(mode.width, mode.height))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(worker) { image ->
                    try { analyzeFeatures(image) } finally { image.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                cameraMessage = "Задняя камера активна · " + mode.width + "×" + mode.height
            }.onFailure { error ->
                running.set(false)
                cameraMessage = "Ошибка камеры: " + error.javaClass.simpleName + ": " + error.message
            }
            refresh()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFeatures(image: androidx.camera.core.ImageProxy) {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width < 16 || height < 16 || pixelStride < 4) return
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var count = 0
        var sumX = 0f
        var sumY = 0f
        var y = 4
        while (y < height - 4) {
            var x = 4
            while (x < width - 4) {
                val offset = y * rowStride + x * pixelStride
                val right = offset + pixelStride
                val down = offset + rowStride
                if (right < bytes.size && down < bytes.size) {
                    val center = bytes[offset].toInt() and 0xff
                    val gx = abs(center - (bytes[right].toInt() and 0xff))
                    val gy = abs(center - (bytes[down].toInt() and 0xff))
                    if (gx + gy > 42) {
                        count++
                        sumX += x.toFloat() / width
                        sumY += y.toFloat() / height
                    }
                }
                x += 8
            }
            y += 8
        }
        val centerX = if (count > 0) sumX / count else Float.NaN
        val centerY = if (count > 0) sumY / count else Float.NaN
        val now = image.imageInfo.timestamp
        synchronized(this) {
            cameraFrames++
            if (lastCameraFrameNs > 0L && now > lastCameraFrameNs) {
                val instant = 1_000_000_000f / (now - lastCameraFrameNs)
                cameraFps = if (cameraFps == 0f) instant else cameraFps * 0.8f + instant * 0.2f
            }
            lastCameraFrameNs = now
            featureCount = count
            if (count > 0 && lastFeatureCenterX.isFinite()) {
                featureMotionX = centerX - lastFeatureCenterX
                featureMotionY = centerY - lastFeatureCenterY
            }
            if (count > 0) {
                lastFeatureCenterX = centerX
                lastFeatureCenterY = centerY
            }
        }
        if (cameraFrames % 5L == 0L) runOnUiThread { refresh() }
    }

    private fun stopCamera() {
        running.set(false)
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraMessage = "Камера остановлена"
        refresh()
    }

    private fun resetMeasurements() {
        synchronized(this) {
            rotationEvents = 0
            accelerationEvents = 0
            cameraFrames = 0
            cameraFps = 0f
            featureCount = 0
            featureMotionX = 0f
            featureMotionY = 0f
            lastCameraFrameNs = 0L
            lastFeatureCenterX = Float.NaN
            lastFeatureCenterY = Float.NaN
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        stopCamera()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(this) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> rotationEvents++
                Sensor.TYPE_ACCELEROMETER -> {
                    accelerationEvents++
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    accelerationMagnitude = sqrt(x*x + y*y + z*z)
                }
            }
        }
        if ((rotationEvents + accelerationEvents) % 40L == 0L) refresh()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun refresh() {
        if (!::report.isInitialized) return
        val values = synchronized(this) {
            Metrics(rotationEvents, accelerationEvents, cameraFrames, cameraFps,
                featureCount, featureMotionX, featureMotionY, accelerationMagnitude)
        }
        report.text = buildString {
            appendLine("Режим: " + mode.name + " · " + mode.fpsLabel)
            appendLine("Rotation-vector events: " + values.rotationEvents + " · " + (rotation?.name ?: "нет"))
            appendLine("Accelerometer events: " + values.accelerationEvents + " · |a|: " + "%.2f".format(values.accelerationMagnitude) + " m/s²")
            appendLine("Camera frames: " + values.cameraFrames + " · FPS: " + "%.1f".format(values.cameraFps))
            appendLine("Image gradient features: " + values.featureCount)
            appendLine("Feature-centroid delta: dx=" + "%.4f".format(values.motionX) + ", dy=" + "%.4f".format(values.motionY))
            appendLine()
            appendLine("Camera: " + cameraMessage)
            appendLine("Status: " + if (running.get()) "RUNNING" else "IDLE")
            appendLine()
            appendLine("Важно: centroid delta — диагностический 2D proxy, не optical flow, не метры и не 6DoF.")
            appendLine("Далее: sparse feature matching + IMU preintegration + калибровка intrinsics/extrinsics.")
        }
    }

    override fun onDestroy() {
        stopCamera()
        worker.shutdown()
        super.onDestroy()
    }
}
