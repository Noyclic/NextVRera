package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrRuntimeTraceJournalTest {
    private fun event(sequence: Long, stage: String = "SESSION", details: String = "ok") =
        EndXrRuntimeTraceEntry(
            sequence = sequence,
            elapsedRealtimeNanos = sequence * 10,
            monotonicNanos = sequence * 10,
            callerPackage = "com.example.game",
            pid = 1234,
            instanceId = "instance-1",
            sessionId = "session-1",
            stage = stage,
            operation = "xrCreateSession",
            result = "XR_SUCCESS",
            resultCode = 0,
            durationNanos = 250,
            frameIndex = 0,
            threadId = 77,
            details = details
        )

    @Test
    fun ringBufferKeepsOnlyNewestEntriesInOrder() {
        var journal = ""
        for (index in 1L..(EndXrRuntimeTraceJournal.MAX_EVENTS + 7L)) {
            journal = EndXrRuntimeTraceJournal.append(journal, event(index))
        }
        assertEquals(EndXrRuntimeTraceJournal.MAX_EVENTS, EndXrRuntimeTraceJournal.count(journal))
        assertTrue(journal.startsWith("{\"sequence\":8,"))
        assertTrue(journal.endsWith("\"details\":\"ok\"}"))
    }

    @Test
    fun jsonEscapesControlCharactersAndQuotes() {
        val json = event(1, details = "egl=\"bad\"\\context\nnext").toJson()
        assertTrue(json.contains("egl=\\\"bad\\\"\\\\context\\nnext"))
        assertFalse(json.contains('\n'))
    }

    @Test
    fun packageFilterDoesNotMixTraceEventsFromDifferentGames() {
        val selected = event(1).toJson()
        val other = event(2).copy(callerPackage = "org.example.other").toJson()
        val filtered = EndXrRuntimeTraceJournal.filterByCallerPackage(
            listOf(selected, other).joinToString("\\n"),
            "com.example.game"
        )

        assertEquals(selected, filtered)
        assertEquals(1, EndXrRuntimeTraceJournal.count(filtered))
        assertEquals("", EndXrRuntimeTraceJournal.filterByCallerPackage(other, ""))
    }

    @Test
    fun jsonArrayAndTextExportRetainFailureSummary() {
        val journal = EndXrRuntimeTraceJournal.append(null, event(1, "LOADER", "loader ok"))
        val jsonArray = EndXrRuntimeTraceJournal.asJsonArray(journal)
        val text = EndXrRuntimeTraceJournal.formatText(
            "com.example.game", 1234, 1, "SESSION", "LOADER", journal
        )
        assertTrue(jsonArray.startsWith("[{"))
        assertTrue(jsonArray.endsWith("}]"))
        assertTrue(text.contains("first_failure_stage=SESSION"))
        assertTrue(text.contains("last_successful_stage=LOADER"))
        assertTrue(text.contains("\"operation\":\"xrCreateSession\""))
    }
}
