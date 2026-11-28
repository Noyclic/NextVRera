package com.noyclic.nextvrera.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var filter: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var remote: ILogcatService? = null
    private var bound = false
    private var bindingRequested = false
    private var captureActive = false
    private var pollingJob: Job? = null
    private val serviceArgs by lazy { createUserServiceArgs() }
    private val preview = StringBuilder()
    private val logFile by lazy { File(filesDir, "last-logcat.txt") }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindLogcatService()
        } else {
            setStatus("Shizuku permission denied — approve it in Shizuku and retry")
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        setStatus("Shizuku connected — tap Connect to authorize")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        bound = false
        bindingRequested = false
        captureActive = false
        pollingJob?.cancel()
        pollingJob = null
        setStatus("Shizuku stopped — capture ended")
        updateButtons()
    }

    private val exportDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) exportLog(uri)
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindingRequested = false
            val binder = service
            if (binder == null) {
                bound = false
                remote = null
                setStatus("Shizuku returned an empty service binder")
                updateButtons()
                return
            }

            remote = ILogcatService.Stub.asInterface(binder)
            bound = remote != null
            val result = runCatching { remote?.startCapture() }.getOrElse {
                "ERROR ${it.javaClass.simpleName}: ${it.message}"
            }

            if (result?.startsWith("CAPTURING") == true) {
                val initialized = runCatching {
                    logFile.writeText("NextVRera Logcat\n$result\n")
                }
                if (initialized.isSuccess) {
                    preview.clear()
                    captureActive = true
                    setStatus(result)
                    renderPreview()
                    startPolling()
                } else {
                    captureActive = false
                    runCatching { remote?.stopCapture() }
                    setStatus("Cannot create log file: ${initialized.exceptionOrNull()?.message}")
                }
            } else {
                captureActive = false
                runCatching { remote?.stopCapture() }
                if (bound) {
                    runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
                }
                remote = null
                bound = false
                setStatus(result ?: "Could not start logcat")
            }
            updateButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
            bindingRequested = false
            captureActive = false
            pollingJob?.cancel()
            pollingJob = null
            setStatus("Logcat service disconnected")
            updateButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        if (bound || bindingRequested) {
            runCatching { remote?.stopCapture() }
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        }
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 12)
        }

        root.addView(TextView(this).apply {
            text = "NextVRera Logcat"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            text = "Checking Shizuku…"
            textSize = 14f
            setPadding(0, 10, 0, 12)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Connect / grant Shizuku permission"
            setOnClickListener { requestShizukuAccess() }
        }, matchWidth())

        filter = EditText(this).apply {
            hint = "Display filter (optional): QNN, DSP, package…"
            singleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    renderPreview()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(filter, matchWidth())

        val captureActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = "Start capture"
            setOnClickListener { requestShizukuAccess() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopCapture() }
        }
        captureActions.addView(startButton, weightedButton())
        captureActions.addView(stopButton, weightedButton())
        root.addView(captureActions, matchWidth())

        val outputActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        outputActions.addView(Button(this).apply {
            text = "Copy log"
            setOnClickListener { copyLogToClipboard() }
        }, weightedButton())
        outputActions.addView(Button(this).apply {
            text = "Export TXT"
            setOnClickListener { exportDocument.launch("nextvrera-logcat.txt") }
        }, weightedButton())
        root.addView(outputActions, matchWidth())

        output = TextView(this).apply {
            text = "No capture yet.\n\n1. Start Shizuku using Wireless debugging.\n2. Tap Connect and approve the permission dialog.\n3. Start capture, reproduce the issue, then stop and export TXT."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(10, 10, 10, 10)
        }
        root.addView(
            ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
        updateButtons()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weightedButton() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun refreshShizukuStatus() {
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!captureActive && !bindingRequested) {
            setStatus(
                if (available) "Shizuku is running — tap Connect to authorize"
                else "Shizuku is not running"
            )
        }
    }

    private fun requestShizukuAccess() {
        try {
            if (!Shizuku.pingBinder()) {
                setStatus("Start Shizuku first using Wireless debugging")
                return
            }
            if (Shizuku.isPreV11()) {
                setStatus("Shizuku API v11 or newer is required")
                return
            }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindLogcatService()
            } else {
                Shizuku.requestPermission(REQUEST_SHIZUKU)
                setStatus("Waiting for Shizuku permission…")
            }
        } catch (error: Throwable) {
            setStatus("Shizuku error: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun createUserServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(packageName, LogcatUserService::class.java.name)
    ).processNameSuffix("logcat").daemon(false).tag("nextvrera-logcat-service").version(1)

    private fun bindLogcatService() {
        if (bound || bindingRequested) {
            setStatus(if (bound) "Logcat service is already connected" else "Connecting to Shizuku service…")
            return
        }
        try {
            bindingRequested = true
            Shizuku.bindUserService(serviceArgs, serviceConnection)
            setStatus("Starting shell-identity logcat service…")
        } catch (error: Throwable) {
            bindingRequested = false
            setStatus("Could not start Shizuku service: ${error.javaClass.simpleName}: ${error.message}")
            updateButtons()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive && captureActive) {
                val chunk = try {
                    remote?.readChunk(180).orEmpty()
                } catch (error: Throwable) {
                    setStatus("Logcat read failed: ${error.javaClass.simpleName}: ${error.message}")
                    stopCapture()
                    break
                }
                if (chunk.isNotBlank()) appendChunk(chunk)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun appendChunk(chunk: String) {
        val bytes = (chunk + "\n").toByteArray(Charsets.UTF_8)
        val remaining = MAX_LOG_BYTES - logFile.length()
        if (remaining <= 0L || bytes.size.toLong() > remaining) {
            stopCapture()
            setStatus("Capture reached the 8 MiB safety limit; export the saved log")
            return
        }

        try {
            logFile.appendBytes(bytes)
        } catch (error: Throwable) {
            stopCapture()
            setStatus("Could not save log; capture stopped: ${error.message}")
            return
        }

        preview.append(chunk).append('\n')
        if (preview.length > MAX_PREVIEW_CHARS) {
            preview.delete(0, preview.length - PREVIEW_TRIM_TO_CHARS)
        }
        renderPreview()
    }

    private fun renderPreview() {
        if (!::output.isInitialized || !::filter.isInitialized) return
        val needle = filter.text.toString().trim().lowercase(Locale.ROOT)
        val visible = if (needle.isEmpty()) preview.toString() else {
            preview.lineSequence()
                .filter { it.lowercase(Locale.ROOT).contains(needle) }
                .joinToString("\n")
        }
        output.text = visible.ifBlank {
            if (captureActive) "Capture is running. No matching lines yet."
            else "No log lines captured yet."
        }
    }

    private fun stopCapture() {
        pollingJob?.cancel()
        pollingJob = null
        captureActive = false
        runCatching { remote?.stopCapture() }
        if (bound) {
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
            bound = false
            remote = null
        }
        bindingRequested = false
        setStatus("Capture stopped — saved in app storage")
        updateButtons()
    }

    private fun updateButtons() {
        if (!::startButton.isInitialized || !::stopButton.isInitialized) return
        startButton.isEnabled = !captureActive && !bindingRequested
        stopButton.isEnabled = captureActive
    }

    private fun setStatus(message: String) {
        if (!::status.isInitialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            status.text = message
        } else {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status.text = message
            }
        }
    }

    private fun copyLogToClipboard() {
        val content = try {
            if (logFile.exists()) logFile.readText() else ""
        } catch (error: Throwable) {
            setStatus("Could not read log: ${error.message}")
            return
        }
        if (content.isBlank()) {
            setStatus("Nothing to copy yet — start capture first")
            return
        }
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("NextVRera Logcat", content))
        }.onSuccess {
            setStatus("Copied ${content.length} characters to clipboard")
        }.onFailure {
            setStatus("Clipboard copy failed: ${it.message}")
        }
    }

    private fun exportLog(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (logFile.exists()) {
                    logFile.inputStream().use { it.copyTo(stream) }
                } else {
                    stream.write("No log captured yet.".toByteArray())
                }
            } ?: error("Could not open export destination")
            setStatus("Exported ${logFile.length()} bytes")
        }.onFailure {
            setStatus("Export failed: ${it.message}")
        }
    }

    companion object {
        private const val REQUEST_SHIZUKU = 4107
        private const val MAX_LOG_BYTES = 8L * 1024L * 1024L
        private const val MAX_PREVIEW_CHARS = 180_000
        private const val PREVIEW_TRIM_TO_CHARS = 140_000
        private const val POLL_INTERVAL_MS = 350L
    }
}
