package com.noyclic.nextvrera.logcat

import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs in Shizuku's shell-identity user-service process. */
class LogcatUserService : ILogcatService.Stub() {
    private val lines = ArrayBlockingQueue<String>(MAX_QUEUED_LINES)
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var droppedLines = 0L

    @Synchronized
    override fun startCapture(): String {
        stopCapture()
        lines.clear()
        droppedLines = 0L

        var child: Process? = null
        return try {
            child = ProcessBuilder(
                "logcat", "-b", "all", "-v", "threadtime", "-T", "1"
            ).redirectErrorStream(true).start()
            if (!child.isAlive) error("logcat exited immediately")

            process = child
            val activeProcess = child
            readerThread = thread(name = "NextVReraLogcatReader", isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(activeProcess.inputStream, Charsets.UTF_8)).use { reader ->
                        while (!Thread.currentThread().isInterrupted) {
                            val line = reader.readLine() ?: break
                            if (!lines.offer(line)) {
                                lines.poll()
                                if (!lines.offer(line)) droppedLines++
                                else droppedLines++
                            }
                        }
                    }
                    val exitCode = runCatching { activeProcess.exitValue() }.getOrNull()
                    if (exitCode != null && exitCode != 0) {
                        offerDiagnostic("! logcat exited with code $exitCode")
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "logcat reader stopped", error)
                    offerDiagnostic("! reader error: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            "CAPTURING uid=${Os.getuid()} pid=${Os.getpid()}"
        } catch (error: Exception) {
            runCatching { child?.destroyForcibly() }
            process = null
            readerThread = null
            "ERROR ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    override fun readChunk(maxLines: Int): String {
        val limit = maxLines.coerceIn(1, MAX_LINES_PER_READ)
        val result = StringBuilder()
        repeat(limit) {
            val line = lines.poll() ?: return@repeat
            if (result.isNotEmpty()) result.append('\n')
            result.append(line)
        }
        val dropped = droppedLines
        if (dropped > 0L) {
            if (result.isNotEmpty()) result.append('\n')
            result.append("! dropped $dropped older log lines because the reader queue was full")
            droppedLines = 0L
        }
        return result.toString()
    }

    @Synchronized
    override fun stopCapture() {
        val oldReader = readerThread
        readerThread = null
        oldReader?.interrupt()

        val oldProcess = process
        process = null
        if (oldProcess != null) {
            runCatching { oldProcess.destroy() }
            val exited = runCatching { oldProcess.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!exited) {
                runCatching { oldProcess.destroyForcibly() }
                runCatching { oldProcess.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            }
            runCatching { oldProcess.inputStream.close() }
            runCatching { oldProcess.errorStream.close() }
        }
        if (oldReader != null && oldReader !== Thread.currentThread()) {
            runCatching { oldReader.join(READER_JOIN_TIMEOUT_MS) }
        }
    }

    override fun destroy() {
        stopCapture()
        System.exit(0)
    }

    private fun offerDiagnostic(message: String) {
        if (!lines.offer(message)) {
            lines.poll()
            lines.offer(message)
        }
    }

    companion object {
        private const val TAG = "NextVReraLogcat"
        private const val MAX_QUEUED_LINES = 4_000
        private const val MAX_LINES_PER_READ = 250
        private const val PROCESS_STOP_TIMEOUT_MS = 250L
        private const val READER_JOIN_TIMEOUT_MS = 500L
    }
}
