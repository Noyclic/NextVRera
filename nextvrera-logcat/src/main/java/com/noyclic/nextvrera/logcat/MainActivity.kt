package com.noyclic.nextvrera.logcat

import android.content.ComponentName
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
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
    private var captureActive = false
    private val preview = StringBuilder()
    private val logFile by lazy { File(filesDir, "last-logcat.txt") }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_SHIZUKU && result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindLogcatService()
        } else if (code == REQUEST_SHIZUKU) {
            setStatus("Shizuku permission denied")
        }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener {
        setStatus("Shizuku connected — tap Connect to authorize")
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        bound = false
        captureActive = false
        setStatus("Shizuku stopped")
        updateButtons()
    }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) exportLog(uri)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = ILogcatService.Stub.asInterface(service)
            bound = true
            val result = runCatching { remote?.startCapture() }.getOrNull()
            if (result?.startsWith("CAPTURING") == true) {
                captureActive = true
                logFile.writeText("NextVRera Logcat\n$result\n")
                preview.clear()
                setStatus(result)
                startPolling()
            } else {
                captureActive = false
                setStatus(result ?: "Could not start logcat")
            }
            updateButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
            captureActive = false
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

    override fun onDestroy() {
        if (bound) {
            runCatching { remote?.stopCapture() }
            runCatching { Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true) }
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
        }
        root.addView(filter, matchWidth())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = "Start capture"
            setOnClickListener { requestShizukuAccess() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopCapture() }
        }
        actions.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, matchWidth())

        root.addView(Button(this).apply {
            text = "Export TXT"
            setOnClickListener { exportDocument.launch("nextvrera-logcat.txt") }
        }, matchWidth())

        output = TextView(this).apply {
            text = "No capture yet.\n\n1. Start Shizuku using Wireless debugging.\n2. Tap Connect and approve the permission dialog.\n3. Start capture, reproduce the issue, then export TXT."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(10, 10, 10, 10)
        }
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun refreshShizukuStatus() {
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        setStatus(if (available) "Shizuku is running — tap Connect to authorize" else "Shizuku is not running")
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
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                setStatus("Permission denied earlier. Allow NextVRera Logcat in Shizuku.")
            } else {
                Shizuku.requestPermission(REQUEST_SHIZUKU)
                setStatus("Waiting for Shizuku permission…")
            }
        } catch (error: Throwable) {
            setStatus("Shizuku error: " + error.javaClass.simpleName + ": " + error.message)
        }
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(packageName, LogcatUserService::class.java.name)
    ).daemon(false).tag("nextvrera-logcat-service").version(1)

    private fun bindLogcatService() {
        if (bound) {
            setStatus("Logcat service is already connected")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs(), serviceConnection)
            setStatus("Starting shell-identity logcat service…")
        } catch (error: Throwable) {
            setStatus("Could not start Shizuku service: " + error.message)
        }
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive && captureActive) {
                val chunk = runCatching { remote?.readChunk(180).orEmpty() }.getOrElse {
                    setStatus("Logcat read failed: " + it.message)
                    ""
                }
                if (chunk.isNotBlank()) appendChunk(chunk)
                delay(350)
            }
        }
    }

    private fun appendChunk(chunk: String) {
        if (logFile.length() >= MAX_LOG_BYTES) {
            stopCapture()
            setStatus("Capture stopped at 8 MB limit; export the log")
            return
        }
        runCatching { logFile.appendText(chunk + "\n") }
            .onFailure { setStatus("Could not save log: " + it.message) }

        preview.append(chunk).append('\n')
        if (preview.length > 180_000) preview.delete(0, preview.length - 140_000)
        val needle = filter.text.toString().trim().lowercase(Locale.ROOT)
        val visible = if (needle.isEmpty()) preview.toString() else preview.lineSequence()
            .filter { it.lowercase(Locale.ROOT).contains(needle) }
            .joinToString("\n")
        output.text = visible.ifBlank { "Capture is running. No matching lines yet." }
    }

    private fun stopCapture() {
        captureActive = false
        runCatching { remote?.stopCapture() }
        if (bound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true) }
            bound = false
            remote = null
        }
        setStatus("Capture stopped — saved in app storage")
        updateButtons()
    }

    private fun updateButtons() {
        startButton.isEnabled = !captureActive
        stopButton.isEnabled = captureActive
    }

    private fun setStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun exportLog(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (logFile.exists()) logFile.inputStream().use { it.copyTo(stream) }
                else stream.write("No log captured yet.".toByteArray())
            } ?: error("Could not open export destination")
            setStatus("Exported " + logFile.length() + " bytes")
        }.onFailure { setStatus("Export failed: " + it.message) }
    }

    companion object {
        private const val REQUEST_SHIZUKU = 4107
        private const val MAX_LOG_BYTES = 8L * 1024L * 1024L
    }
}
