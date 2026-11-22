package com.noyclic.questphonexr.tracking

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

data class LiteRtStandaloneModelFiles(
    val palmDetector: File,
    val handLandmark: File
)

/**
 * Integrity-pinned, explicit-download storage for the two independent LiteRT hand models.
 * No model is downloaded at startup; partial/corrupt downloads never replace verified files.
 */
object LiteRtStandaloneModelStore {
    const val MODEL_REVISION = "5699e8b7740034d58235fe95ae61a1606ea448e2"
    const val DETECTOR_FILE_NAME = "HandDetector.tflite"
    const val LANDMARK_FILE_NAME = "HandLandmarkDetector.tflite"
    const val DETECTOR_SIZE_BYTES = 7_072_880L
    const val LANDMARK_SIZE_BYTES = 8_070_980L
    const val DETECTOR_SHA256 =
        "7fed965f73757224de6c7f9251781b924f242e753b1246c708525b015c6dc371"
    const val LANDMARK_SHA256 =
        "628856ec43b60e828c86d9ffac562f076484d1763242060e4cf16be08d36fb29"

    private const val MODEL_DIRECTORY = "litert-standalone-models"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val USER_AGENT = "NextVRera-LiteRT-Standalone/1.0"

    private data class Asset(
        val id: String,
        val fileName: String,
        val url: String,
        val expectedBytes: Long,
        val expectedSha256: String
    )

    private val detectorAsset = Asset(
        id = "palm_detector",
        fileName = DETECTOR_FILE_NAME,
        url = "https://huggingface.co/qualcomm/MediaPipe-Hand-Detection/resolve/$MODEL_REVISION/$DETECTOR_FILE_NAME",
        expectedBytes = DETECTOR_SIZE_BYTES,
        expectedSha256 = DETECTOR_SHA256
    )
    private val landmarkAsset = Asset(
        id = "hand_landmark",
        fileName = LANDMARK_FILE_NAME,
        url = "https://huggingface.co/qualcomm/MediaPipe-Hand-Detection/resolve/$MODEL_REVISION/$LANDMARK_FILE_NAME",
        expectedBytes = LANDMARK_SIZE_BYTES,
        expectedSha256 = LANDMARK_SHA256
    )

    fun detectorFile(context: Context): File = modelFile(context, detectorAsset)
    fun landmarkFile(context: Context): File = modelFile(context, landmarkAsset)

    fun installedFiles(context: Context): LiteRtStandaloneModelFiles? {
        val detector = detectorFile(context)
        val landmark = landmarkFile(context)
        if (!verify(detector, DETECTOR_SIZE_BYTES, DETECTOR_SHA256) ||
            !verify(landmark, LANDMARK_SIZE_BYTES, LANDMARK_SHA256)
        ) return null
        return LiteRtStandaloneModelFiles(detector, landmark)
    }

    fun areInstalled(context: Context): Boolean = installedFiles(context) != null

    /** Call on a worker thread after an explicit install request. */
    fun download(
        context: Context,
        onProgress: (modelId: String, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> }
    ): LiteRtStandaloneModelFiles {
        val detector = downloadAsset(context, detectorAsset, onProgress)
        val landmark = downloadAsset(context, landmarkAsset, onProgress)
        return LiteRtStandaloneModelFiles(detector, landmark)
    }

    private fun modelFile(context: Context, asset: Asset): File =
        File(File(context.applicationContext.noBackupFilesDir, MODEL_DIRECTORY), asset.fileName)

    private fun downloadAsset(
        context: Context,
        asset: Asset,
        onProgress: (modelId: String, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val destination = modelFile(context, asset)
        if (verify(destination, asset.expectedBytes, asset.expectedSha256)) return destination
        val parent = destination.parentFile
            ?: throw IllegalStateException("LiteRT model directory is unavailable.")
        check(parent.exists() || parent.mkdirs()) {
            "Could not create the private LiteRT model directory."
        }

        val temporary = File(parent, destination.name + ".part")
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "LiteRT ${asset.id} download failed with HTTP ${connection.responseCode}."
            }
            val advertisedLength = connection.contentLengthLong
            check(advertisedLength == -1L || advertisedLength == asset.expectedBytes) {
                "LiteRT ${asset.id} download has an unexpected Content-Length."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("LiteRT ${asset.id} download was interrupted.")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        check(downloaded <= asset.expectedBytes) {
                            "LiteRT ${asset.id} download exceeds its pinned size."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(asset.id, downloaded, asset.expectedBytes)
                    }
                    output.fd.sync()
                }
            }

            check(downloaded == asset.expectedBytes) {
                "LiteRT ${asset.id} download is incomplete: $downloaded bytes."
            }
            check(digest.digest().toHex() == asset.expectedSha256) {
                "LiteRT ${asset.id} SHA-256 verification failed; downloaded file was discarded."
            }

            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            check(verify(destination, asset.expectedBytes, asset.expectedSha256)) {
                "LiteRT ${asset.id} failed post-install integrity verification."
            }
            return destination
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    internal fun verify(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        return sha256(file).equals(expectedSha256, ignoreCase = true)
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
