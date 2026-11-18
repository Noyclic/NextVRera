package com.noyclic.hardwareprobe

import android.content.Context
import com.qualcomm.qti.QnnDelegate
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Captures diagnostics below the LiteRT Java wrapper:
 * - stages Hexagon DSP skeletons from APK ZIP entries into app-private storage;
 * - points QNN at that real directory instead of assuming PackageManager extracted DSP ELF files;
 * - enables a verbose log level when the installed QNN Java API exposes one;
 * - captures this process's native QNN/FastRPC logcat output around delegate creation;
 * - asks JNI to inspect the loaded QNN modules and Android FastRPC environment.
 */
internal class QnnDeepDiagnostics(
    context: Context,
    private val nativeEnvironmentProbe: (String, String) -> String,
    private val loadedModulesProbe: (String) -> String
) {
    private val appContext = context.applicationContext
    private val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
    private val skeletonDirectory = File(appContext.noBackupFilesDir, "qnn-skeletons")

    fun prepareSkeletons(): String {
        val report = mutableListOf<String>()
        report += "=== QNN DSP SKELETON PACKAGING ==="
        report += "APK: ${appContext.applicationInfo.sourceDir}"
        report += "nativeLibraryDir: $nativeLibraryDir"
        report += "private skeleton dir: ${skeletonDirectory.absolutePath}"

        val apkFiles = linkedSetOf<String>()
        apkFiles += appContext.applicationInfo.sourceDir
        appContext.applicationInfo.splitSourceDirs?.forEach { apkFiles += it }

        var foundEntries = 0
        var extracted = 0
        val errors = mutableListOf<String>()
        if (!skeletonDirectory.exists() && !skeletonDirectory.mkdirs()) {
            report += "ERROR: could not create private skeleton directory"
        }

        for (apkPath in apkFiles) {
            val apk = File(apkPath)
            if (!apk.isFile) {
                errors += "APK not readable: $apkPath"
                continue
            }
            try {
                ZipFile(apk).use { zip ->
                    val entries: Enumeration<out java.util.zip.ZipEntry> = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (!entry.name.matches(Regex("lib/arm64-v8a/(libQnn[A-Za-z0-9]*Skel\\.so)"))) continue

                        foundEntries++
                        val fileName = entry.name.substringAfterLast('/')
                        val destination = File(skeletonDirectory, fileName)
                        val temporary = File(skeletonDirectory, "$fileName.part")
                        try {
                            val digest = MessageDigest.getInstance("SHA-256")
                            var copied = 0L
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(temporary).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        output.write(buffer, 0, count)
                                        digest.update(buffer, 0, count)
                                        copied += count
                                    }
                                }
                            }

                            val header = ByteArray(20)
                            FileInputStream(temporary).use { input ->
                                var offset = 0
                                while (offset < header.size) {
                                    val count = input.read(header, offset, header.size - offset)
                                    if (count <= 0) break
                                    offset += count
                                }
                                check(offset == header.size) { "truncated ELF header" }
                            }
                            check(
                                header[0] == 0x7f.toByte() &&
                                    header[1] == 'E'.code.toByte() &&
                                    header[2] == 'L'.code.toByte() &&
                                    header[3] == 'F'.code.toByte()
                            ) { "not an ELF file" }
                            val elfClass = header[4].toInt() and 0xff
                            val machine = (header[18].toInt() and 0xff) or
                                ((header[19].toInt() and 0xff) shl 8)
                            check(machine == 164) {
                                "unexpected ELF machine=$machine (class=$elfClass); expected QUALCOMM_DSP6=164"
                            }
                            check(elfClass == 1) { "unexpected ELF class=$elfClass; expected DSP ELF32" }
                            if (destination.exists()) destination.delete()
                            check(temporary.renameTo(destination)) { "atomic rename failed" }
                            destination.setReadable(true, true)
                            report += "EXTRACTED $fileName: $copied bytes, ELF32/QUALCOMM_DSP6, sha256=${digest.digest().joinToString("") { "%02x".format(it) }}"
                            extracted++
                        } catch (error: Throwable) {
                            temporary.delete()
                            errors += "$fileName: ${error.javaClass.simpleName}: ${error.message}"
                        }
                    }
                }
            } catch (error: Throwable) {
                errors += "$apkPath: ${error.javaClass.simpleName}: ${error.message}"
            }
        }

        report += "APK skeleton entries found: $foundEntries"
        report += "DSP skeleton files extracted: $extracted"
        val materialized = skeletonDirectory.listFiles()
            ?.filter { it.isFile && it.name.endsWith("Skel.so") }
            .orEmpty()
        report += "private directory contains: " +
            (materialized.joinToString { it.name + " (" + it.length() + " bytes)" }.ifBlank { "none" })

        val nativeDirSkels = File(nativeLibraryDir).listFiles()
            ?.filter { it.isFile && it.name.endsWith("Skel.so") }
            .orEmpty()
        report += "nativeLibraryDir skeletons: " +
            (nativeDirSkels.joinToString { it.name }.ifBlank { "none" })

        if (foundEntries > 0 && nativeDirSkels.isEmpty() && extracted > 0) {
            report += "NOTE: DSP ELF files are present inside lib/arm64-v8a in the APK but were not materialized in nativeLibraryDir. QNN will be pointed at the extracted app-private directory."
        }
        errors.take(12).forEach { report += "EXTRACTION WARNING: $it" }
        return report.joinToString("\n")
    }

    fun skeletonDirectoryForDelegate(): String {
        val hasSkeletons = skeletonDirectory.listFiles()
            ?.any { it.isFile && it.name.endsWith("Skel.so") } == true
        return if (hasSkeletons) skeletonDirectory.absolutePath else nativeLibraryDir
    }

    fun nativeEnvironmentReport(): String =
        nativeEnvironmentProbe(nativeLibraryDir, skeletonDirectoryForDelegate())

    fun loadedModulesReport(): String =
        loadedModulesProbe(skeletonDirectoryForDelegate())

    fun configureDelegate(options: QnnDelegate.Options): String {
        val directory = skeletonDirectoryForDelegate()
        options.setSkelLibraryDir(directory)
        return buildString {
            append("QNN setSkelLibraryDir: ")
            append(directory)
            append("\n")
            append(enableVerboseLoggingIfSupported(options))
        }
    }

    private fun enableVerboseLoggingIfSupported(options: QnnDelegate.Options): String {
        val methods = options.javaClass.methods
        val setters = methods.filter {
            it.name.equals("setLogLevel", ignoreCase = true) && it.parameterCount == 1
        }
        if (setters.isEmpty()) {
            val related = methods
                .filter { it.name.contains("log", ignoreCase = true) || it.name.contains("debug", ignoreCase = true) }
                .map { it.toGenericString() }
            return "QNN Java log-level setter: not exposed" +
                if (related.isEmpty()) "" else " (related API: ${related.joinToString("; ")})"
        }

        val outcomes = mutableListOf<String>()
        for (setter in setters) {
            val parameter = setter.parameterTypes[0]
            val value: Any? = when {
                parameter.isEnum -> {
                    val constants = parameter.enumConstants?.toList().orEmpty()
                    constants.firstOrNull { it.toString().contains("DEBUG", true) }
                        ?: constants.firstOrNull { it.toString().contains("VERBOSE", true) }
                }
                parameter == Int::class.javaPrimitiveType || parameter == Int::class.javaObjectType -> 5
                else -> null
            }
            if (value == null) {
                outcomes += "${setter.toGenericString()} (unsupported parameter type)"
                continue
            }
            try {
                setter.invoke(options, value)
                outcomes += "VERBOSE requested via ${setter.toGenericString()} = $value"
                break
            } catch (error: Throwable) {
                outcomes += "FAILED ${setter.toGenericString()}: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
        return "QNN log-level reflection: " + outcomes.joinToString("; ").ifBlank { "no compatible setter" }
    }

    fun startLogCapture(): LogCapture = LogCapture()

    class LogCapture {
        private val lines = Collections.synchronizedList(mutableListOf<String>())
        private var process: Process? = null
        private var readerThread: Thread? = null
        private var startupError: String? = null

        fun start(): String {
            return try {
                val pid = android.os.Process.myPid()
                val child = ProcessBuilder(
                    "/system/bin/logcat",
                    "-v",
                    "threadtime",
                    "--pid=$pid"
                ).redirectErrorStream(true).start()
                process = child
                readerThread = Thread({
                    try {
                        BufferedReader(InputStreamReader(child.inputStream)).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                synchronized(lines) {
                                    if (lines.size < MAX_LOG_LINES) lines.add(line)
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        synchronized(lines) {
                            if (lines.size < MAX_LOG_LINES) {
                                lines.add("LOGCAT_READER_ERROR: ${error.javaClass.simpleName}: ${error.message}")
                            }
                        }
                    }
                }, "qnn-logcat-reader").apply {
                    isDaemon = true
                    start()
                }
                "started logcat for app PID $pid (no global log buffer clearing)"
            } catch (error: Throwable) {
                startupError = "${error.javaClass.simpleName}: ${error.message}"
                "could not start logcat: $startupError"
            }
        }

        fun stop(): List<String> {
            val child = process
            if (child != null) {
                runCatching {
                    child.destroy()
                    if (!child.waitFor(600, TimeUnit.MILLISECONDS)) child.destroyForcibly()
                }
            }
            runCatching { readerThread?.join(900) }
            val result = synchronized(lines) { lines.toList() }.toMutableList()
            if (startupError != null) result.add("LOGCAT_START_ERROR: $startupError")
            if (result.isEmpty()) {
                result += "No logcat lines captured for this PID. Native QNN logging may be disabled or Android may restrict this process's log view."
            }
            return result
        }

        companion object {
            private const val MAX_LOG_LINES = 350
        }
    }
}
