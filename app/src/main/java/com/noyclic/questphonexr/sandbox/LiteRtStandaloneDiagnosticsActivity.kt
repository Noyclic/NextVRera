package com.noyclic.questphonexr.sandbox

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.noyclic.questphonexr.tracking.LiteRtStandaloneHtpModelProbe
import com.noyclic.questphonexr.tracking.LiteRtStandaloneModelStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-invoked model installation and strict Qualcomm HTP inspection.
 * This screen is diagnostic only; it never activates or publishes the standalone provider.
 */
class LiteRtStandaloneDiagnosticsActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NextVRera-LiteRT-HTP-Probe").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var installButton: Button
    private lateinit var probeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        buildUi()
        refreshInstalledState()
    }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "LITERT STANDALONE · QNN HTP"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        root.addView(TextView(this).apply {
            text = "Отдельная проверка двух моделей. MediaPipe и текущий трекер не изменяются."
            textSize = 14f
            setTextColor(0xffaebbc7.toInt())
            setPadding(0, 8, 0, 14)
        })
        installButton = Button(this).apply {
            text = "СКАЧАТЬ ДВЕ МОДЕЛИ · около 15 МБ"
            setOnClickListener { installModels() }
        }
        root.addView(installButton)
        probeButton = Button(this).apply {
            text = "ПРОВЕРИТЬ ОБЕ МОДЕЛИ ЧЕРЕЗ QNN HTP"
            isEnabled = false
            setOnClickListener { runHtpProbe() }
        }
        root.addView(probeButton)
        root.addView(Button(this).apply {
            text = "НАЗАД"
            setOnClickListener { finish() }
        })
        val scroll = ScrollView(this)
        status = TextView(this).apply {
            text = "Модели ещё не проверены."
            textSize = 13f
            setTextColor(0xffd7e0e8.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 16, 12, 16)
            setBackgroundColor(0xff101820.toInt())
        }
        scroll.addView(status)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun refreshInstalledState() {
        val installed = runCatching { LiteRtStandaloneModelStore.areInstalled(this) }.getOrDefault(false)
        probeButton.isEnabled = installed
        if (installed) {
            status.text = "Обе модели установлены и прошли проверку размера + SHA-256.\n" +
                "Можно запустить строгую проверку QNN HTP."
        }
    }

    private fun installModels() {
        if (!busy.compareAndSet(false, true)) return
        setBusy(true)
        setStatus("Загрузка закреплённых моделей Qualcomm…")
        worker.execute {
            try {
                val files = LiteRtStandaloneModelStore.download(this) { id, downloaded, total ->
                    val percent = if (total > 0L) downloaded * 100L / total else 0L
                    setStatus("$id: $percent% ($downloaded / $total bytes)")
                }
                val verified = LiteRtStandaloneModelStore.installedFiles(this)
                check(verified != null) { "Модельная пара не прошла итоговую проверку SHA-256." }
                setStatus(
                    "МОДЕЛИ УСТАНОВЛЕНЫ И ПРОВЕРЕНЫ\n" +
                        "${files.palmDetector.name}: ${files.palmDetector.length()} bytes\n" +
                        "${files.handLandmark.name}: ${files.handLandmark.length()} bytes\n\n" +
                        "Следующий шаг: строгая проверка QNN HTP."
                )
                runOnUiThread { if (!isFinishing && !isDestroyed) probeButton.isEnabled = true }
            } catch (error: Throwable) {
                setStatus("ОШИБКА УСТАНОВКИ\n${error.javaClass.simpleName}: ${error.message}")
            } finally {
                busy.set(false)
                setBusy(false)
            }
        }
    }

    private fun runHtpProbe() {
        if (!busy.compareAndSet(false, true)) return
        setBusy(true)
        setStatus("QNN HTP: инициализация… GPU / NNAPI / XNNPACK выключены.")
        worker.execute {
            val files = LiteRtStandaloneModelStore.installedFiles(this)
            if (files == null) {
                setStatus("ОШИБКА: модели отсутствуют или не прошли SHA-256 проверку.")
                busy.set(false)
                setBusy(false)
                return@execute
            }
            val reports = listOf(
                "PALM DETECTOR" to files.palmDetector,
                "HAND LANDMARK" to files.handLandmark
            ).map { (label, file) ->
                val report = runCatching {
                    LiteRtStandaloneHtpModelProbe(this, file).use { it.inspectAndWarmup() }
                }
                label to report
            }
            val text = buildString {
                reports.forEach { (label, result) ->
                    append("\n[$label]\n")
                    result.onSuccess { info ->
                        append("QNN HTP: PASS (warm-up executed)\n")
                        append("Input: ${info.input.name} ${info.input.shape} ${info.input.dataType}\n")
                        info.outputs.forEach { output ->
                            append("Output ${output.index}: ${output.name} ${output.shape} ${output.dataType}\n")
                        }
                        append("Warm-up: ${"%.2f".format(info.warmupLatencyMs)} ms\n")
                        append("Median: ${"%.2f".format(info.medianInferenceLatencyMs)} ms · ")
                        append("P95: ${"%.2f".format(info.p95InferenceLatencyMs)} ms\n")
                        append("Operator placement: ${info.operatorPlacement}\n")
                    }.onFailure { error ->
                        append("QNN HTP: FAIL\n")
                        append("${error.javaClass.simpleName}: ${error.message}\n")
                    }
                }
                append("\nВАЖНО: успешный warm-up не доказывает, что каждый оператор исполнялся на HTP.")
                append("\nЭта проверка не включает provider в EndXR и не переключает MediaPipe.")
            }
            setStatus(text)
            busy.set(false)
            setBusy(false)
        }
    }

    private fun setStatus(value: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) status.text = value
        }
    }

    private fun setBusy(value: Boolean) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                installButton.isEnabled = !value
                probeButton.isEnabled = !value &&
                    runCatching { LiteRtStandaloneModelStore.areInstalled(this) }.getOrDefault(false)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
