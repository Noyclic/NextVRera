package com.noyclic.literthtpprobe

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NextVRera-QNN-HTP").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val recentErrors = java.util.ArrayDeque<String>()
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var probe: Button
    private lateinit var showErrors: Button
    private lateinit var copyReport: Button

    @Volatile private var modelsReady = false
    @Volatile private var latestReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "NEXTVRERA · QUALCOMM QNN HTP PROBE"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        root.addView(TextView(this).apply {
            text = "Изолированный тест. Основное приложение и MediaPipe не затрагиваются."
            textSize = 13f
            setTextColor(0xffb8c5d0.toInt())
            setPadding(0, 8, 0, 12)
        })

        install = Button(this).apply {
            text = "СКАЧАТЬ МОДЕЛИ · ~15 МБ"
            setOnClickListener { downloadModels() }
        }
        root.addView(install)

        probe = Button(this).apply {
            text = "ЗАПУСТИТЬ СТРОГИЙ QNN HTP ТЕСТ"
            isEnabled = false
            setOnClickListener { runProbe() }
        }
        root.addView(probe)

        showErrors = Button(this).apply {
            text = "ПОКАЗАТЬ ОШИБКИ"
            setOnClickListener { showRecentErrors() }
        }
        root.addView(showErrors)

        copyReport = Button(this).apply {
            text = "СКОПИРОВАТЬ ОТЧЁТ"
            isEnabled = false
            setOnClickListener { copyLatestReport() }
        }
        root.addView(copyReport)
        root.addView(Button(this).apply {
            text = "ЗАКРЫТЬ"
            setOnClickListener { finish() }
        })

        val scroll = ScrollView(this)
        status = TextView(this).apply {
            text = "Модели не установлены."
            textSize = 13f
            setTextColor(0xffe0e8ef.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 16, 12, 16)
            setBackgroundColor(0xff101820.toInt())
        }
        scroll.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        modelsReady = ModelStore.installed(this) != null
        probe.isEnabled = modelsReady
        if (modelsReady) {
            status.text = "Обе модели установлены и SHA-256 проверены.\nМожно запускать HTP тест."
        }
    }

    private fun downloadModels() {
        if (!busy.compareAndSet(false, true)) return
        setBusy(true)
        setStatus("Загрузка закреплённых моделей Qualcomm…")
        worker.execute {
            try {
                val pair = ModelStore.download(this) { name, done, total ->
                    val percent = if (total > 0L) done * 100L / total else 0L
                    setStatus("$name: $percent% ($done / $total bytes)")
                }
                check(ModelStore.installed(this) != null) {
                    "Final SHA-256 verification failed."
                }
                modelsReady = true
                setStatus(
                    "МОДЕЛИ УСТАНОВЛЕНЫ\n" +
                        "${pair.detector.name}: ${pair.detector.length()} bytes\n" +
                        "${pair.landmark.name}: ${pair.landmark.length()} bytes\n" +
                        "Нажми кнопку строгого HTP теста."
                )
            } catch (error: Throwable) {
                val message = "DOWNLOAD FAILED: ${error.javaClass.simpleName}: ${error.message}"
                recordError(message)
                setStatus(message)
            } finally {
                busy.set(false)
                setBusy(false)
            }
        }
    }

    private fun runProbe() {
        if (!busy.compareAndSet(false, true)) return
        setBusy(true)
        setStatus("QNN HTP: delegate initialization. NNAPI / XNNPACK disabled.")
        worker.execute {
            try {
                val pair = ModelStore.installed(this)
                if (pair == null) {
                    modelsReady = false
                    val message = "ERROR: models are missing or failed SHA-256 verification."
                    recordError(message)
                    latestReport = message
                    setStatus(message)
                    return@execute
                }

                val modelRuns = listOf(
                    Triple("PALM DETECTOR", HtpModelKind.PALM_DETECTOR, pair.detector),
                    Triple("HAND LANDMARK", HtpModelKind.HAND_LANDMARK, pair.landmark)
                )
                val results = modelRuns.map { (label, kind, file) ->
                    Triple(label, kind, runCatching {
                        HtpModelProbe(this, file).use { it.run() }
                    })
                }
                val report = buildString {
                    appendLine("NEXTVRERA · STRICT QNN HTP / TENSOR CONTRACT REPORT")
                    appendLine("Backend: QNN HTP_BACKEND only · NNAPI=false · XNNPACK=false")
                    appendLine("Input tensors are required to be FLOAT32; output quantization metadata is reported as exported.")
                    results.forEach { (label, kind, result) ->
                        appendLine()
                        appendLine("[$label]")
                        result.onSuccess { runtime ->
                            val contract = HtpModelContractValidator.validate(kind, runtime)
                            appendLine("QNN HTP warm-up: PASS")
                            appendLine("Input: ${formatTensor(runtime.input)}")
                            runtime.outputs.forEach { output ->
                                appendLine("Output ${output.index}: ${formatTensor(output)}")
                            }
                            append(contract.format())
                            appendLine("Warm-up: ${"%.2f".format(runtime.warmupMs)} ms")
                            appendLine("Median: ${"%.2f".format(runtime.medianMs)} ms · P95: ${"%.2f".format(runtime.p95Ms)} ms")
                            appendLine("Operator placement: ${runtime.placement}")
                            if (!contract.passed) {
                                recordError("$label: tensor contract failed\n${contract.format()}")
                            }
                        }.onFailure { error ->
                            val message = "QNN HTP: ${error.javaClass.simpleName}: ${error.message}"
                            appendLine(message)
                            recordError("$label: $message")
                        }
                    }
                    appendLine()
                    appendLine("A successful warm-up does not prove complete HTP operator placement.")
                    appendLine("Contract PASS validates shapes only; it does not prove inference accuracy or live two-hand tracking.")
                    appendLine("This isolated APK does not activate the EndXR tracker.")
                }
                latestReport = report
                setStatus(report)
            } catch (error: Throwable) {
                val message = "PROBE FAILED: ${error.javaClass.simpleName}: ${error.message}"
                recordError(message)
                latestReport = message
                setStatus(message)
            } finally {
                busy.set(false)
                setBusy(false)
            }
        }
    }

    private fun formatTensor(tensor: TensorInfo): String =
        "${tensor.name} shape=${tensor.shape} signature=${tensor.shapeSignature} " +
            "type=${tensor.type} bytes=${tensor.bytes} elements=${tensor.elementCount} " +
            "quant(scale=${tensor.quantizationScale}, zeroPoint=${tensor.quantizationZeroPoint})"

    private fun recordError(message: String) {
        synchronized(recentErrors) {
            recentErrors.addLast(message)
            while (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeFirst()
        }
    }

    private fun showRecentErrors() {
        val entries = synchronized(recentErrors) { recentErrors.toList() }
        val message = if (entries.isEmpty()) {
            "Ошибок пока не зарегистрировано. Ошибки загрузки, инициализации QNN, warm-up и проверки контрактов появятся здесь."
        } else {
            entries.asReversed().joinToString("\n\n")
        }
        val content = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(0xffe0e8ef.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(18, 14, 18, 14)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Последние ошибки · QNN HTP")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun copyLatestReport() {
        val report = latestReport
        if (report.isBlank()) {
            Toast.makeText(this, "Отчёт ещё не создан.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NextVRera QNN HTP report", report))
        Toast.makeText(this, "Полный отчёт скопирован.", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(value: String) {
        runOnUiThread { if (!isFinishing) status.text = value }
    }

    private fun setBusy(value: Boolean) {
        runOnUiThread {
            if (!isFinishing) {
                install.isEnabled = !value
                probe.isEnabled = !value && modelsReady
                showErrors.isEnabled = !value
                copyReport.isEnabled = !value && latestReport.isNotBlank()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val MAX_RECENT_ERRORS = 24
    }
}
