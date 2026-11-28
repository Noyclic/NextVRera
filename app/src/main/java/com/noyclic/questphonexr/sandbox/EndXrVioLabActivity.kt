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
import com.noyclic.questphonexr.sandbox.vio.GrayFrame
import com.noyclic.questphonexr.sandbox.vio.VioFusionEngine
import com.noyclic.questphonexr.sandbox.vio.VioPose
import com.noyclic.questphonexr.sandbox.vio.VisualMotionEstimate
import com.noyclic.questphonexr.sandbox.vio.Vec3
import kotlin.math.min
import kotlin.math.sqrt

/** Sandbox VIO lab: sparse image flow + IMU orientation, with explicit scale limitations. */
class EndXrVioLabActivity : ComponentActivity(), SensorEventListener {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private lateinit var sensors: SensorManager
    private var rotation: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private val vio = VioFusionEngine()
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeAnalysis: ImageAnalysis? = null
    private lateinit var report: TextView
    private var rotationEvents = 0L
    private var accelerationEvents = 0L
    private var gyroscopeEvents = 0L
    private var skippedCameraFrames = 0L
    private var nextFrameAllowedNs = 0L
    @Volatile private var lastVisualEstimate: VisualMotionEstimate? = null
    @Volatile private var lastPose: VioPose? = null
    private var cameraFrames = 0L
    private var lastCameraFrameNs = 0L
    private var cameraFps = 0f
    private var featureCount = 0
    private var accelerationMagnitude = 0f
    private var mode = Mode.BALANCED
    private var cameraMessage = "Камера выключена"

    private data class Metrics(
        val rotationEvents: Long,
        val accelerationEvents: Long,
        val gyroscopeEvents: Long,
        val cameraFrames: Long,
        val skippedCameraFrames: Long,
        val cameraFps: Float,
        val featureCount: Int,
        val accelerationMagnitude: Float
    )

    private enum class Mode(val width: Int, val height: Int, val maxFps: Int) {
        ECONOMY(320, 240, 8),
        BALANCED(480, 360, 15),
        PRECISION(640, 480, 24)
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
        gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
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
        nextFrameAllowedNs = 0L
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                activeAnalysis = analysis
                cameraMessage = "Задняя камера активна · " + mode.width + "×" + mode.height
            }.onFailure { error ->
                running.set(false)
                cameraMessage = "Ошибка камеры: " + error.javaClass.simpleName + ": " + error.message
            }
            refresh()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFeatures(image: androidx.camera.core.ImageProxy) {
        val timestamp = image.imageInfo.timestamp
        synchronized(this) {
            val interval = 1_000_000_000L / mode.maxFps
            if (nextFrameAllowedNs != 0L && timestamp < nextFrameAllowedNs) {
                skippedCameraFrames++
                return
            }
            nextFrameAllowedNs = timestamp + interval
        }
        val plane = image.planes.firstOrNull() ?: return
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width < 32 || height < 32 || pixelStride < 4) return
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Downsample to a fixed 160x120 luminance grid before CPU feature detection.
        // This bounds per-frame work on the Snapdragon 685 and keeps descriptor geometry stable.
        val sampleWidth = 160
        val sampleHeight = 120
        val gray = ByteArray(sampleWidth * sampleHeight)
        for (sampleY in 0 until sampleHeight) {
            val sourceY = min(height - 1, sampleY * height / sampleHeight)
            for (sampleX in 0 until sampleWidth) {
                val sourceX = min(width - 1, sampleX * width / sampleWidth)
                val offset = sourceY * rowStride + sourceX * pixelStride
                if (offset + 2 >= bytes.size) continue
                val red = bytes[offset].toInt() and 0xff
                val green = bytes[offset + 1].toInt() and 0xff
                val blue = bytes[offset + 2].toInt() and 0xff
                gray[sampleY * sampleWidth + sampleX] =
                    ((red * 77 + green * 150 + blue * 29) shr 8).toByte()
            }
        }
        val estimate = vio.onVisualFrame(GrayFrame(timestamp, sampleWidth, sampleHeight, gray))
        val pose = vio.pose(timestamp)
        synchronized(this) {
            cameraFrames++
            if (lastCameraFrameNs > 0L && timestamp > lastCameraFrameNs) {
                val instant = 1_000_000_000f / (timestamp - lastCameraFrameNs)
                cameraFps = if (cameraFps == 0f) instant else cameraFps * 0.8f + instant * 0.2f
            }
            lastCameraFrameNs = timestamp
            featureCount = estimate.inliers
            lastVisualEstimate = estimate
            lastPose = pose
        }
        if (cameraFrames % 3L == 0L) runOnUiThread { refresh() }
    }

    private fun stopCamera() {
        running.set(false)
        val provider = cameraProvider
        val analysis = activeAnalysis
        if (provider != null && analysis != null) {
            runCatching { provider.unbind(analysis) }
        }
        activeAnalysis = null
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
            gyroscopeEvents = 0L
            skippedCameraFrames = 0L
            nextFrameAllowedNs = 0L
            lastVisualEstimate = null
            lastPose = null
            lastCameraFrameNs = 0L
        }
        vio.reset("sandbox lab measurements reset")
        refresh()
    }

    override fun onResume() {
        super.onResume()
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        stopCamera()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                vio.onRotationVector(event.timestamp, event.values)
                synchronized(this) { rotationEvents++ }
            }
            Sensor.TYPE_GYROSCOPE -> {
                vio.onGyroscope(event.timestamp, Vec3(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                ))
                synchronized(this) { gyroscopeEvents++ }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val acceleration = Vec3(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
                vio.onAccelerometer(event.timestamp, acceleration)
                synchronized(this) {
                    accelerationEvents++
                    accelerationMagnitude = sqrt(
                        acceleration.x * acceleration.x +
                            acceleration.y * acceleration.y +
                            acceleration.z * acceleration.z
                    ).toFloat()
                }
            }
        }
        if ((rotationEvents + accelerationEvents + gyroscopeEvents) % 40L == 0L) refresh()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun refresh() {
        if (!::report.isInitialized) return
        val values = synchronized(this) {
            Metrics(
                rotationEvents, accelerationEvents, gyroscopeEvents, cameraFrames,
                skippedCameraFrames, cameraFps, featureCount, accelerationMagnitude
            )
        }
        val visual = lastVisualEstimate
        val pose = lastPose
        val imu = vio.diagnostics()
        report.text = buildString {
            appendLine("Режим: " + mode.name + " · cap " + mode.maxFps + " FPS")
            appendLine("Rotation-vector: " + values.rotationEvents + " · " + (rotation?.name ?: "нет"))
            appendLine("Gyroscope: " + values.gyroscopeEvents + " · " + (gyroscope?.name ?: "нет"))
            appendLine("Accelerometer: " + values.accelerationEvents + " · |a| " + "%.2f".format(values.accelerationMagnitude) + " m/s²")
            appendLine("Camera frames: " + values.cameraFrames + " · FPS " + "%.1f".format(values.cameraFps))
            appendLine("Frames skipped by cap: " + values.skippedCameraFrames)
            appendLine("Sparse features / inliers: " + (visual?.matches ?: 0) + " / " + (visual?.inliers ?: 0))
            appendLine("Visual flow: " + (visual?.reason ?: "waiting for camera"))
            appendLine("Visual confidence: " + "%.2f".format(visual?.confidence ?: 0.0))
            appendLine()
            appendLine("Pose: " + (pose?.diagnostic ?: "waiting"))
            appendLine("Orientation source: " + imu.orientationSource)
            appendLine("Relative position: " + (pose?.position?.let {
                "%.4f, %.4f, %.4f".format(it.x, it.y, it.z)
            } ?: "—"))
            appendLine("Translation scale: " + (pose?.translationScale?.name ?: "UNKNOWN"))
            appendLine("IMU samples / visual accepted: " + imu.imuSamples + " / " + imu.acceptedVisualUpdates)
            appendLine()
            appendLine("Важно: это sparse 2D flow + IMU orientation. Перенос пока относительный, не метры; полноценная 6DoF и масштаб не подтверждены.")
            appendLine("Камера: " + cameraMessage + " · " + if (running.get()) "RUNNING" else "IDLE")
        }
    }

    override fun onDestroy() {
        stopCamera()
        worker.shutdown()
        super.onDestroy()
    }
}
