package com.noyclic.questphonexr.tracking

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtStandaloneModelStoreTest {

    @Test
    fun modelAssetsArePinnedToOneImmutableRevision() {
        assertEquals("5699e8b7740034d58235fe95ae61a1606ea448e2", LiteRtStandaloneModelStore.MODEL_REVISION)
        assertEquals("HandDetector.tflite", LiteRtStandaloneModelStore.DETECTOR_FILE_NAME)
        assertEquals("HandLandmarkDetector.tflite", LiteRtStandaloneModelStore.LANDMARK_FILE_NAME)
        assertEquals(7_072_880L, LiteRtStandaloneModelStore.DETECTOR_SIZE_BYTES)
        assertEquals(8_070_980L, LiteRtStandaloneModelStore.LANDMARK_SIZE_BYTES)
        assertEquals(64, LiteRtStandaloneModelStore.DETECTOR_SHA256.length)
        assertEquals(64, LiteRtStandaloneModelStore.LANDMARK_SHA256.length)
    }

    @Test
    fun verificationRequiresBothExactSizeAndSha256() {
        val file = File.createTempFile("litert-standalone-model", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val hash = LiteRtStandaloneModelStore.sha256(file)
            assertTrue(LiteRtStandaloneModelStore.verify(file, 5L, hash))
            assertFalse(LiteRtStandaloneModelStore.verify(file, 6L, hash))
            assertFalse(LiteRtStandaloneModelStore.verify(file, 5L, "0".repeat(64)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingModelFilesAreNeverReportedAsVerified() {
        val file = File.createTempFile("litert-standalone-missing", ".bin")
        try {
            file.delete()
            assertFalse(
                LiteRtStandaloneModelStore.verify(
                    file,
                    LiteRtStandaloneModelStore.DETECTOR_SIZE_BYTES,
                    LiteRtStandaloneModelStore.DETECTOR_SHA256
                )
            )
        } finally {
            file.delete()
        }
    }
}
