package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingHealthDiagnosticsTest {
    @Test
    fun formatsRecoveredActiveStateAsRecovered() {
        val tracker = TrackingHealthTracker(gracePeriodMs = 50L, staleAfterMs = 200L)
        tracker.onFrameAccepted(10L)
        assertTrue(tracker.tick(250L))
        tracker.onFrameAccepted(260L)
        val snapshot = tracker.snapshot()
        assertEquals(TrackingHealthDiagnostics.Verdict.RECOVERED, TrackingHealthDiagnostics.verdict(snapshot))
        val report = TrackingHealthDiagnostics.format(snapshot)
        assertTrue(report.contains("tracking_health_verdict=recovered"))
        assertTrue(report.contains("tracking_health_recoveries=1"))
    }

    @Test
    fun formatsGraceAsDegradedAndStaleAsStale() {
        val tracker = TrackingHealthTracker(gracePeriodMs = 100L, staleAfterMs = 300L)
        tracker.onFrameAccepted(0L)
        tracker.tick(150L)
        assertEquals(TrackingHealthDiagnostics.Verdict.DEGRADED, TrackingHealthDiagnostics.verdict(tracker.snapshot()))
        tracker.tick(300L)
        assertEquals(TrackingHealthDiagnostics.Verdict.STALE, TrackingHealthDiagnostics.verdict(tracker.snapshot()))
    }
}
