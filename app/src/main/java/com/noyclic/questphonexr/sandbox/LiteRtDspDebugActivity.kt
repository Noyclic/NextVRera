package com.noyclic.questphonexr.sandbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandRoiCrop
import com.noyclic.questphonexr.tracking.HandRoiRgbPreprocessor
import com.noyclic.questphonexr.tracking.RtmposeHandOutputDecoder
import com.noyclic.questphonexr.tracking.RtmposeHandSimccTensors
import com.noyclic.questphonexr.tracking.LiteRtFloatTensor
import com.noyclic.questphonexr.tracking.RtmposeHandDecodeResult
import com.noyclic.questphonexr.tracking.RtmposeModelStore
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Isolated QNN DSP diagnostic. CameraX ImageAnalysis runs with no Preview surface.
 * RTMPose is a top-down landmark model, not a palm detector; two seeded ROIs independently
 * follow their own 21-point outputs. No MediaPipe/GPU/NNAPI/XNNPACK delegate fallback.
 */
private data class DspHandTrack(
    val slot: Int,
    val seedCrop: HandRoiCrop,
    var crop: HandRoiCrop = seedCrop,
    var points: List<HandPoint> = emptyList(),
    var missedFrames: Int = 0
)

class LiteRtDspDebugActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EndXR-LiteRT-QNN-DSP").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var runner: QnnDspRtmposeRunner? = null
    private lateinit var overlay: HandSkeletonOverlay
    private lateinit var status: TextView
    private lateinit var details: TextView
    private var argb = IntArray(0)
    private val input = FloatArray(3 * 256 * 256)
    private var tracks: List<DspHandTrack> = emptyList()
    private var fpsStart = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var lastUiUpdate = 0L
    private var inferenceMs = 0.0
    private var pipelineMs = 0.0
    private var visibleHandCount = 0
    private companion object { const val TRACK_REACQUIRE_MISSES = 10 }

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initialize() else fail("CAMERA permission denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) initialize() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        overlay = HandSkeletonOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            text = "LiteRT DSP HAND TRACKER\nQNN DSP: INITIALIZING"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 14, 16, 14)
            setBackgroundColor(0xCC101820.toInt())
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        details = TextView(this).apply {
            text = "Камера скрыта. Две руки: левая и правая зоны."
            textSize = 13f
            setTextColor(0xffd0d7de.toInt())
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xAA101820.toInt())
        }
        root.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(
            Button(this).apply {
                text = "НАЗАД"
                setOnClickListener { finish() }
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = 90
                marginEnd = 10
            }
        )
        setContentView(root)
    }

    private fun initialize() {
        setStatus("QNN DSP: INITIALIZING · verifying RTMPose model")
        worker.execute {
            try {
                val file = RtmposeModelStore.download(this)
                val readyRunner = QnnDspRtmposeRunner(this, file)
                runner = readyRunner
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        worker.execute { readyRunner.close() }
                    } else {
                        setStatus("QNN DSP delegate configured · warm-up inference PASS\nOperator placement: NOT VERIFIED · CPU op fallback: UNKNOWN")
                        bindCamera()
                    }
                }
            } catch (t: Throwable) {
                fail("QNN DSP INIT FAILED\n" + t.javaClass.simpleName + ": " + t.message)
            }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(worker) { frame ->
                    try {
                        if (runner != null && busy.compareAndSet(false, true)) process(frame)
                    } catch (t: Throwable) {
                        fail("QNN DSP INFERENCE FAILED\n" + t.javaClass.simpleName + ": " + t.message)
                    } finally {
                        busy.set(false)
                        frame.close()
                    }
                }
                provider.unbindAll()
                // Analysis-only: no Preview use-case, so no camera pixels are rendered.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                setStatus("QNN DSP: CAMERA RUNNING · dual-ROI tracker · waiting for two-hand results")
            } catch (t: Throwable) {
                fail("FRONT CAMERA FAILED\n" + t.javaClass.simpleName + ": " + t.message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(frame: ImageProxy) {
        val pipelineStart = SystemClock.elapsedRealtimeNanos()
        val plane = frame.planes.firstOrNull() ?: error("RGBA plane missing.")
        val width = frame.width
        val height = frame.height
        val count = width.toLong() * height.toLong()
        require(count in 1..4_000_000) { "Invalid camera frame dimensions." }
        if (argb.size != count.toInt()) argb = IntArray(count.toInt())

        val buffer = plane.buffer
        val base = buffer.position()
        val limit = buffer.limit()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = base + y * plane.rowStride + x * plane.pixelStride
                require(p >= base && p + 3 < limit) { "RGBA plane stride exceeds its buffer." }
                val r = buffer.get(p).toInt() and 255
                val g = buffer.get(p + 1).toInt() and 255
                val b = buffer.get(p + 2).toInt() and 255
                val a = buffer.get(p + 3).toInt() and 255
                argb[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val rotation = frame.imageInfo.rotationDegrees
        val uprightWidth = if (rotation == 90 || rotation == 270) height else width
        val uprightHeight = if (rotation == 90 || rotation == 270) width else height
        if (tracks.isEmpty() || tracks.any {
                it.crop.sourceWidthPx != uprightWidth || it.crop.sourceHeightPx != uprightHeight
            }
        ) {
            val seeds = LiteRtDspTwoHandRoiPolicy.createSeedCrops(uprightWidth, uprightHeight)
            check(seeds.size == 2) { "Camera frame is too small to seed two independent hand ROIs." }
            tracks = seeds.mapIndexed { index, seed -> DspHandTrack(index, seed) }
        }

        val activeTracks = tracks
        var totalInferenceNanos = 0L
        val candidates = activeTracks.map { track ->
            check(
                HandRoiRgbPreprocessor.writeRtmposeImageNetNchwFloat(
                    sourceArgb = argb,
                    crop = track.crop,
                    outputSizePx = 256,
                    destinationNchw = input,
                    sourceRotationDegrees = rotation
                )
            ) { "RTMPose preprocessing rejected ROI " + (track.slot + 1) + "." }

            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            val outputs = checkNotNull(runner).infer(input)
            totalInferenceNanos += SystemClock.elapsedRealtimeNanos() - inferenceStart

            val decoded = RtmposeHandOutputDecoder.decode(
                outputs = RtmposeHandSimccTensors(
                    LiteRtFloatTensor(intArrayOf(1, 21, 512), outputs.first),
                    LiteRtFloatTensor(intArrayOf(1, 21, 512), outputs.second)
                ),
                crop = track.crop,
                frameTimestampMs = SystemClock.elapsedRealtime(),
                // This is a manually seeded ROI association, not a model confidence score.
                // Geometry validation below is the actual publication gate.
                roiConfidence = 1f
            )
            val points = (decoded as? RtmposeHandDecodeResult.Success)
                ?.frame?.hands?.singleOrNull()?.points.orEmpty()
            if (LiteRtDspTwoHandRoiPolicy.hasPlausibleHand(points, track.crop)) {
                points
            } else {
                emptyList()
            }
        }.toMutableList()

        // Two independent top-down crops can occasionally converge on the same physical
        // hand. Suppress the duplicate rather than drawing two skeletons over one hand.
        if (candidates[0].size == 21 && candidates[1].size == 21 &&
            !LiteRtDspTwoHandRoiPolicy.areDistinctHands(candidates[0], candidates[1])
        ) {
            val firstCost = associationCost(activeTracks[0], candidates[0])
            val secondCost = associationCost(activeTracks[1], candidates[1])
            if (firstCost <= secondCost) candidates[1] = emptyList()
            else candidates[0] = emptyList()
        }

        activeTracks.forEachIndexed { index, track ->
            val points = candidates[index]
            if (points.size == 21) {
                track.points = points
                track.crop = nextCrop(points, uprightWidth, uprightHeight, track.crop)
                track.missedFrames = 0
            } else {
                track.points = emptyList()
                track.missedFrames++
                if (track.missedFrames >= TRACK_REACQUIRE_MISSES) {
                    track.crop = track.seedCrop
                    track.missedFrames = 0
                }
            }
        }

        inferenceMs = totalInferenceNanos / 1_000_000.0
        pipelineMs = (SystemClock.elapsedRealtimeNanos() - pipelineStart) / 1_000_000.0
        visibleHandCount = activeTracks.count { it.points.size == 21 }
        overlay.submit(
            activeTracks.filter { it.points.size == 21 }
                .map { DspHandOverlayHand(it.slot, it.points) }
        )
        updateStats()
    }

    private fun associationCost(track: DspHandTrack, points: List<HandPoint>): Float {
        val expected = track.points.firstOrNull() ?: HandPoint(
            x = (track.seedCrop.leftPx + track.seedCrop.sizePx * 0.5f) /
                track.seedCrop.sourceWidthPx.toFloat(),
            y = (track.seedCrop.topPx + track.seedCrop.sizePx * 0.5f) /
                track.seedCrop.sourceHeightPx.toFloat(),
            z = 0f
        )
        val wrist = points.first()
        val dx = wrist.x - expected.x
        val dy = wrist.y - expected.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun nextCrop(
        points: List<HandPoint>,
        width: Int,
        height: Int,
        previous: HandRoiCrop
    ): HandRoiCrop {
        val candidate = HandRoiCrop.fromLandmarks(
            points, width, height, paddingFactor = 1.9f, minimumCropPx = 96
        ) ?: return previous
        val oldX = previous.leftPx + previous.sizePx * 0.5f
        val oldY = previous.topPx + previous.sizePx * 0.5f
        val newX = candidate.leftPx + candidate.sizePx * 0.5f
        val newY = candidate.topPx + candidate.sizePx * 0.5f
        val maxJump = minOf(width, height) * 0.30f
        return if (kotlin.math.abs(newX - oldX) <= maxJump &&
            kotlin.math.abs(newY - oldY) <= maxJump
        ) candidate else previous
    }

    private fun updateStats() {
        val now = SystemClock.elapsedRealtime()
        if (fpsStart == 0L) fpsStart = now
        fpsFrames++
        if (now - fpsStart >= 1000L) {
            fps = fpsFrames * 1000f / (now - fpsStart).toFloat()
            fpsFrames = 0
            fpsStart = now
        }
        if (now - lastUiUpdate < 250L) return
        lastUiUpdate = now
        runOnUiThread {
            status.text = String.format(
                Locale.US,
                "QNN DSP delegate configured · 2 independent inference calls/frame · placement NOT VERIFIED\nFPS %.1f | hands %d/2 | inference total %.1f ms (%.1f ms/ROI) | pipeline %.1f ms\nGPU / NNAPI / XNNPACK delegates disabled · unsupported ops may run on CPU",
                fps, visibleHandCount, inferenceMs, inferenceMs / 2.0, pipelineMs
            )
            details.text =
                "Две независимые ROI · два DSP inference на кадр · голубая/оранжевая рука\n" +
                "Начни с рук в левой и правой половинах. RTMPose не ищет ладони по всему кадру;\n" +
                "presence score отсутствует, пустые ROI отсекаются геометрическим фильтром."
        }
    }

    private fun setStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun fail(value: String) {
        runOnUiThread {
            status.text = value + "\nNo alternate delegate is configured; unsupported operators may still use default CPU kernels."
            cameraProvider?.unbindAll()
            details.text = "Камера остаётся скрытой. Ошибка не маскируется переключением на GPU или CPU."
            overlay.submit(emptyList())
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        worker.execute { runCatching { runner?.close() }; runner = null }
        worker.shutdown()
        super.onDestroy()
    }
}

private data class DspHandOverlayHand(
    val slot: Int,
    val points: List<HandPoint>
)

private class HandSkeletonOverlay(context: Context) : View(context) {
    @Volatile private var hands: List<DspHandOverlayHand> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3348a9c7
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val edges = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17
    )

    fun submit(value: List<DspHandOverlayHand>) {
        hands = value.toList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val guideTop = height * 0.18f
        val guideBottom = height * 0.82f
        canvas.drawRoundRect(width * 0.03f, guideTop, width * 0.47f, guideBottom, 18f, 18f, guide)
        canvas.drawRoundRect(width * 0.53f, guideTop, width * 0.97f, guideBottom, 18f, 18f, guide)
        canvas.drawLine(width * 0.5f, guideTop, width * 0.5f, guideBottom, guide)

        hands.forEach { hand ->
            val current = hand.points
            if (current.size != 21) return@forEach
            val tint = if (hand.slot == 0) 0xff38d9ff.toInt() else 0xffffb45b.toInt()
            line.color = tint
            dot.color = tint
            fun x(i: Int): Float = (1f - current[i].x.coerceIn(0f, 1f)) * width
            fun y(i: Int): Float = current[i].y.coerceIn(0f, 1f) * height
            edges.forEach { (a, b) -> canvas.drawLine(x(a), y(a), x(b), y(b), line) }
            current.indices.forEach { i ->
                canvas.drawCircle(x(i), y(i), if (i == 0) 8f else 5.5f, dot)
            }
        }
    }
}

/** Hexagon DSP skeletons are ELF32 DSP images, not ARM libraries to dlopen in the app. */
private object QnnDspSkeletonStore {
    fun prepare(context: Context): String {
        val app = context.applicationContext
        val dir = File(app.noBackupFilesDir, "qnn-dsp-skeletons")
        check(dir.exists() || dir.mkdirs()) { "Cannot create QNN DSP skeleton directory." }
        dir.listFiles()?.filter { it.name.endsWith("Skel.so") || it.name.endsWith(".part") }
            ?.forEach { check(it.delete()) { "Cannot clear stale QNN skeleton: " + it.name } }
        val apkFiles = linkedSetOf(app.applicationInfo.sourceDir)
        app.applicationInfo.splitSourceDirs?.forEach(apkFiles::add)
        apkFiles.forEach { apkPath ->
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory ||
                        !entry.name.matches(Regex("lib/arm64-v8a/(libQnn[A-Za-z0-9]*Skel\\.so)"))
                    ) continue
                    val name = entry.name.substringAfterLast('/')
                    val temp = File(dir, "$name.part")
                    val target = File(dir, name)
                    zip.getInputStream(entry).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    val header = ByteArray(20)
                    FileInputStream(temp).use { input ->
                        var offset = 0
                        while (offset < header.size) {
                            val n = input.read(header, offset, header.size - offset)
                            if (n <= 0) break
                            offset += n
                        }
                        check(offset == 20) { "Truncated DSP ELF header: " + name }
                    }
                    val machine = (header[18].toInt() and 255) or
                        ((header[19].toInt() and 255) shl 8)
                    check(
                        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                            (header[4].toInt() and 255) == 1 && machine == 164
                    ) { name + " is not ELF32 QUALCOMM_DSP6." }
                    if (target.exists()) target.delete()
                    check(temp.renameTo(target)) { "Could not install DSP skeleton " + name }
                    target.setReadable(true, true)
                }
            }
        }
        check(dir.listFiles()?.any { it.name.endsWith("Skel.so") } == true) {
            "No QNN DSP skeleton was packaged in the APK."
        }
        return dir.absolutePath
    }
}

/** Interpreter + DSP_BACKEND path copied from the verified Hardware Probe integration. */
private class QnnDspRtmposeRunner(context: Context, modelFile: File) : Closeable {
    private val interpreter: Interpreter
    private val delegate: QnnDelegate
    private val inputBuffer: ByteBuffer
    private val outputX: ByteBuffer
    private val outputY: ByteBuffer
    private val outputXSize: Int
    private val outputYSize: Int

    init {
        check(RtmposeModelStore.verify(modelFile)) { "RTMPose model SHA-256 verification failed." }
        val bytes = modelFile.readBytes()
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        val options = Interpreter.Options().apply {
            setUseNNAPI(false)
            setUseXNNPACK(false)
        }
        val qnnOptions = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.DSP_BACKEND)
            setSkelLibraryDir(QnnDspSkeletonStore.prepare(context))
        }
        val createdDelegate = QnnDelegate(qnnOptions)
        var partialInterpreter: Interpreter? = null
        try {
            options.addDelegate(createdDelegate)
            val created = Interpreter(model, options)
            partialInterpreter = created
            val inputTensor = created.getInputTensor(0)
            val xTensor = created.getOutputTensor(0)
            val yTensor = created.getOutputTensor(1)
            check(inputTensor.dataType() == DataType.FLOAT32 &&
                inputTensor.shape().contentEquals(intArrayOf(1, 3, 256, 256))
            ) { "Unexpected RTMPose input tensor type/shape." }
            check(created.outputTensorCount == 2) { "RTMPose should expose two SimCC outputs." }
            check(xTensor.dataType() == DataType.FLOAT32 &&
                xTensor.shape().contentEquals(intArrayOf(1, 21, 512))
            ) { "Unexpected RTMPose X output tensor." }
            check(yTensor.dataType() == DataType.FLOAT32 &&
                yTensor.shape().contentEquals(intArrayOf(1, 21, 512))
            ) { "Unexpected RTMPose Y output tensor." }
            interpreter = created
            delegate = createdDelegate
            inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            outputX = ByteBuffer.allocateDirect(xTensor.numBytes()).order(ByteOrder.nativeOrder())
            outputY = ByteBuffer.allocateDirect(yTensor.numBytes()).order(ByteOrder.nativeOrder())
            outputXSize = xTensor.numElements()
            outputYSize = yTensor.numElements()
            // Warm-up executes a real inference through the configured QNN DSP delegate.
            infer(FloatArray(inputTensor.numElements()))
            partialInterpreter = null
        } catch (t: Throwable) {
            runCatching { partialInterpreter?.close() }
            runCatching { createdDelegate.javaClass.getMethod("close").invoke(createdDelegate) }
            throw t
        }
    }

    fun infer(values: FloatArray): Pair<FloatArray, FloatArray> {
        require(values.size == inputBuffer.capacity() / Float.SIZE_BYTES) {
            "Unexpected RTMPose input size."
        }
        inputBuffer.clear()
        inputBuffer.asFloatBuffer().put(values)
        outputX.clear()
        outputY.clear()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), mapOf(0 to outputX, 1 to outputY))
        val x = FloatArray(outputXSize)
        val y = FloatArray(outputYSize)
        outputX.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(x)
        outputY.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(y)
        check(x.all { it.isFinite() } && y.all { it.isFinite() }) {
            "QNN delegate returned non-finite RTMPose output."
        }
        return x to y
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { delegate.javaClass.getMethod("close").invoke(delegate) }
    }
}
