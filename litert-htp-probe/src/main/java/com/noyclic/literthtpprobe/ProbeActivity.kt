package com.noyclic.literthtpprobe

import android.app.Activity
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
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "NextVRera-QNN-HTP").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var probe: Button
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
        install = Button(this).apply { text = "СКАЧАТЬ МОДЕЛИ · ~15 МБ"; setOnClickListener { downloadModels() } }
        root.addView(install)
        probe = Button(this).apply {
            text = "ЗАПУСТИТЬ СТРОГИЙ QNN HTP ТЕСТ"
            isEnabled = false
            setOnClickListener { runProbe() }
        }
        root.addView(probe)
        copyReport = Button(this).apply {
            text = "СКОПИРОВАТЬ ОТЧЁТ"
            isEnabled = false
            setOnClickListener { copyLatestReport() }
        }
        root.addView(copyReport)
        root.addView(Button(this).apply { text = "ЗАКРЫТЬ"; setOnClickListener { finish() } })
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
        if (modelsReady) status.text = "Обе модели установлены и SHA-256 проверены.\nМожно запускать HTP тест."
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
                check(ModelStore.installed(this) != null) { "Final SHA-256 verification failed." }
                modelsReady = true
                setStatus(
                    "МОДЕЛИ УСТАНОВЛЕНЫ\n" +
                        "${pair.detector.name}: ${pair.detector.length()} bytes\n" +
                        "${pair.landmark.name}: ${pair.landmark.length()} bytes\n" +
                        "Нажми кнопку строгого HTP теста."
                )
            } catch (error: Throwable) {
                setStatus("DOWNLOAD FAILED\n${error.javaClass.simpleName}: ${error.message}")
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
            val pair = ModelStore.installed(this)
            if (pair == null) {
                modelsReady = false
                val report = "ERROR: models are missing or failed SHA-256 verification."
                latestReport = report
                setStatus(report)
                busy.set(false)
                setBusy(false)
                return@execute
            }
            val results = listOf("PALM DETECTOR" to pair.detector, "HAND LANDMARK" to pair.landmark)
                .map { (label, file) -> label to runCatching { HtpModelProbe(this, file).use { it.run() } } }
            val report = buildString {
                results.forEach { (label, result) ->
                    append("\n[$label]\n")
                    result.onSuccess { r ->
                        append("QNN HTP warm-up: PASS\n")
                        append("Input: ${r.input.name} ${r.input.shape} ${r.input.type}\n")
                        r.outputs.forEach { out -> append("Output ${out.index}: ${out.name} ${out.shape} ${out.type}\n") }
                        append("Warm-up: ${"%.2f".format(r.warmupMs)} ms\n")
                        append("Median: ${"%.2f".format(r.medianMs)} ms · P95: ${"%.2f".format(r.p95Ms)} ms\n")
                        append("Operator placement: ${r.placement}\n")
                    }.onFailure { e -> append("QNN HTP: FAIL\n${e.javaClass.simpleName}: ${e.message}\n") }
                }
                append("\nУспешный warm-up не доказывает полное размещение операций на HTP.")
                append("\nЭтот APK не активирует трекер EndXR.")
            }
            latestReport = report
            setStatus(report)
            busy.set(false)
            setBusy(false)
        }
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
                copyReport.isEnabled = !value && latestReport.isNotBlank()
            }
        }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
