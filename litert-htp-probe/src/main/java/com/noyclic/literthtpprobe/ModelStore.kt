package com.noyclic.literthtpprobe

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class ModelPair(val detector: File, val landmark: File)

object ModelStore {
    const val REVISION = "5699e8b7740034d58235fe95ae61a1606ea448e2"
    const val DETECTOR_SIZE = 7_072_880L
    const val LANDMARK_SIZE = 8_070_980L
    const val DETECTOR_SHA256 = "7fed965f73757224de6c7f9251781b924f242e753b1246c708525b015c6dc371"
    const val LANDMARK_SHA256 = "628856ec43b60e828c86d9ffac562f076484d1763242060e4cf16be08d36fb29"
    private const val DIR = "models"
    private const val BUFFER = 64 * 1024

    private data class Asset(val name: String, val size: Long, val hash: String) {
        val url get() = "https://huggingface.co/qualcomm/MediaPipe-Hand-Detection/resolve/$REVISION/$name"
    }
    private val detector = Asset("HandDetector.tflite", DETECTOR_SIZE, DETECTOR_SHA256)
    private val landmark = Asset("HandLandmarkDetector.tflite", LANDMARK_SIZE, LANDMARK_SHA256)

    fun installed(context: Context): ModelPair? {
        val d = file(context, detector)
        val l = file(context, landmark)
        return if (verify(d, detector) && verify(l, landmark)) ModelPair(d, l) else null
    }

    fun download(context: Context, progress: (String, Long, Long) -> Unit): ModelPair {
        return ModelPair(downloadOne(context, detector, progress), downloadOne(context, landmark, progress))
    }

    private fun file(context: Context, asset: Asset) =
        File(File(context.applicationContext.noBackupFilesDir, DIR), asset.name)

    private fun downloadOne(context: Context, asset: Asset, progress: (String, Long, Long) -> Unit): File {
        val destination = file(context, asset)
        if (verify(destination, asset)) return destination
        val parent = checkNotNull(destination.parentFile)
        check(parent.exists() || parent.mkdirs()) { "Cannot create private model directory." }
        val temp = File(parent, destination.name + ".part")
        val connection = URL(asset.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${connection.responseCode} while downloading ${asset.name}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp, false).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Model download interrupted." }
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        count += n
                        check(count <= asset.size) { "Unexpected model size." }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        progress(asset.name, count, asset.size)
                    }
                    output.fd.sync()
                }
            }
            check(count == asset.size) { "Incomplete model download: $count bytes." }
            check(digest.digest().hex() == asset.hash) { "SHA-256 mismatch for ${asset.name}." }
            try {
                Files.move(temp.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(verify(destination, asset)) { "Post-install model verification failed." }
            return destination
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun verify(file: File, asset: Asset): Boolean =
        file.isFile && file.length() == asset.size && hash(file) == asset.hash

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
