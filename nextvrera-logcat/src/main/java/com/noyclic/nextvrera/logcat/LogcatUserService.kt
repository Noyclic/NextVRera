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
    private val lines = ArrayBlockingQueue<String>(4_000)
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null

    @Synchronized
    override fun startCapture(): String {
        stopCapture()
        lines.clear()
        return try {
            val child = ProcessBuilder("logcat", "-v", "threadtime", "-T", "1")
                .redirectErrorStream(true)
                .start()
            process = child
            readerThread = thread(name = "NextVReraLogcatReader", isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(child.inputStream)).use { reader ->
                        while (!Thread.currentThread().isInterrupted) {
                            val line = reader.readLine() ?: break
                            if (!lines.offer(line)) {
                                lines.poll()
                                lines.offer(line)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Log.w("NextVReraLogcat", "logcat reader stopped", error)
                    lines.offer("! reader error: " + error.javaClass.simpleName + ": " + error.message)
                }
            }
            "CAPTURING uid=" + Os.getuid() + " pid=" + Os.getpid()
        } catch (error: Throwable) {
            process = null
            "ERROR " + error.javaClass.simpleName + ": " + error.message
        }
    }

    override fun readChunk(maxLines: Int): String {
        val result = StringBuilder()
        repeat(maxLines.coerceIn(1, 250)) {
            val line = lines.poll() ?: return@repeat
            if (result.isNotEmpty()) result.append('\n')
            result.append(line)
        }
        return result.toString()
    }

    @Synchronized
    override fun stopCapture() {
        readerThread?.interrupt()
        readerThread = null
        process?.let { child ->
            runCatching { child.destroy() }
            runCatching {
                if (!child.waitFor(250, TimeUnit.MILLISECONDS)) child.destroyForcibly()
            }
        }
        process = null
    }

    override fun destroy() {
        stopCapture()
        System.exit(0)
    }
}
