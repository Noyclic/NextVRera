package com.noyclic.literdsp

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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "NextVRera-QNN-DSP").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var probe: Button
    @Volatile private var modelsReady = false
    @Volatile private var lastReport = "No report yet."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,18,18,18); setBackgroundColor(Color.BLACK) }
        root.addView(TextView(this).apply {
            text = "NEXTVRERA · QUALCOMM QNN DSP PROBE"; textSize = 18f
            setTextColor(Color.WHITE); typeface = android.graphics.Typeface.MONOSPACE
        })
        root.addView(TextView(this).apply {
            text = "Изолированный тест двух Qualcomm hand-моделей. Камера не используется; MediaPipe и основное приложение не затрагиваются."
            textSize = 13f; setTextColor(0xffb8c5d0.toInt()); setPadding(0,8,0,12)
        })
        install = Button(this).apply { text = "СКАЧАТЬ МОДЕЛИ · ~15 МБ"; setOnClickListener { downloadModels() } }
        root.addView(install)
        probe = Button(this).apply {
            text = "ЗАПУСТИТЬ СТРОГИЙ QNN DSP ТЕСТ"; isEnabled = false
            setOnClickListener { runProbe() }
        }
        root.addView(probe)
        root.addView(Button(this).apply {
            text = "КОПИРОВАТЬ ОТЧЁТ"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("NextVRera QNN DSP report", lastReport))
                status.text = lastReport + "\n\nОтчёт скопирован."
            }
        })
        val scroll = ScrollView(this)
        status = TextView(this).apply {
            text = "Модели не установлены."; textSize = 13f; setTextColor(0xffe0e8ef.toInt())
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(12,16,12,16); setBackgroundColor(0xff101820.toInt())
        }
        scroll.addView(status); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f)); setContentView(root); refresh()
    }
    private fun refresh() {
        modelsReady = ModelStore.installed(this) != null
        probe.isEnabled = modelsReady
        if (modelsReady) status.text = "Обе модели установлены и SHA-256 проверены. Можно запускать DSP тест."
    }
    private fun downloadModels() {
        if (!busy.compareAndSet(false,true)) return
        setBusy(true); setStatus("Загрузка закреплённых Qualcomm моделей…")
        worker.execute {
            try {
                val pair = ModelStore.download(this) { name, done, total ->
                    setStatus("$name: ${if(total>0) done*100/total else 0}% ($done / $total bytes)")
                }
                modelsReady = true
                setStatus("МОДЕЛИ ПРОВЕРЕНЫ\n${pair.detector.name}: ${pair.detector.length()} bytes\n${pair.landmark.name}: ${pair.landmark.length()} bytes\nНажми DSP test.")
            } catch (e: Throwable) { setStatus("DOWNLOAD FAILED\n${e.javaClass.simpleName}: ${e.message}") }
            finally { busy.set(false); setBusy(false) }
        }
    }
    private fun runProbe() {
        if (!busy.compareAndSet(false,true)) return
        setBusy(true); setStatus("QNN DSP: delegate initialization. NNAPI/XNNPACK disabled.")
        worker.execute {
            val pair = ModelStore.installed(this)
            if (pair == null) { modelsReady=false; setStatus("ERROR: model files missing or SHA mismatch."); busy.set(false); setBusy(false); return@execute }
            val results = listOf("PALM DETECTOR" to pair.detector, "HAND LANDMARK" to pair.landmark).map { (label,file) ->
                label to runCatching { DspModelProbe(this,file).use { it.run() } }
            }
            val report = buildString {
                append("NextVRera QNN DSP probe · ${java.time.Instant.now()}\n")
                append("Models revision: ${ModelStore.REVISION}\nBackend requested: QNN DSP_BACKEND\n")
                results.forEach { (label,result) ->
                    append("\n[$label]\n")
                    result.onSuccess { r ->
                        append("Delegate: ${r.delegate}\nInvocation: ${r.invocation}\n")
                        append("Input: ${r.input.name} ${r.input.shape} ${r.input.type} · ${r.input.bytes} bytes\n")
                        r.outputs.forEach { append("Output ${it.index}: ${it.name} ${it.shape} ${it.type} · ${it.bytes} bytes\n") }
                        append("Warm-up: ${"%.2f".format(r.warmupMs)} ms · median: ${"%.2f".format(r.medianMs)} ms · P95: ${"%.2f".format(r.p95Ms)} ms\n")
                        append("Operator placement: ${r.operatorPlacement}\n")
                    }.onFailure { e -> append("QNN DSP: FAIL\n${e.javaClass.simpleName}: ${e.message}\n") }
                }
                append("\nIMPORTANT: zero-filled input validates model/delegate invocation only, not hand detection quality.")
                append("\nOperator placement remains NOT_VERIFIED unless backend instrumentation proves it.")
                append("\nNo camera is opened; no tracker is activated.")
            }
            lastReport = report; setStatus(report); busy.set(false); setBusy(false)
        }
    }
    private fun setStatus(value: String) { runOnUiThread { if(!isFinishing) status.text=value } }
    private fun setBusy(value: Boolean) { runOnUiThread { if(!isFinishing) { install.isEnabled=!value; probe.isEnabled=!value && modelsReady } } }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
