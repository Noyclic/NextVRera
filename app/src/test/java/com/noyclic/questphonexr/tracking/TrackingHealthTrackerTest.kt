package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingHealthTrackerTest {
    @Test
    fun shortGapStaysInGraceAndClearsOnlyOnceAfterStaleThreshold() {
        val tracker = TrackingHealthTracker(gracePeriodMs = 100L, staleAfterMs = 300L)

        tracker.onFrameAccepted(1L)
        assertFalse(tracker.tick(50L))
        assertEquals(TrackingHealthTracker.State.ACTIVE, tracker.snapshot().state)

        tracker.tick(150L)
        assertEquals(TrackingHealthTracker.State.GRACE, tracker.snapshot().state)
        assertEquals(1L, tracker.snapshot().gapCount)

        assertTrue(tracker.tick(301L))
        assertEquals(TrackingHealthTracker.State.STALE, tracker.snapshot().state)
        assertFalse(tracker.tick(500L))
        assertEquals(1L, tracker.snapshot().staleTransitions)
    }

    @Test
    fun acceptedFrameAfterStaleCountsRecoveryAndReturnsActive() {
        val tracker = TrackingHealthTracker(gracePeriodMs = 50L, staleAfterMs = 200L)

        tracker.onFrameAccepted(10L)
        assertTrue(tracker.tick(250L))
        tracker.onFrameAccepted(260L)

        val snapshot = tracker.snapshot()
        assertEquals(TrackingHealthTracker.State.ACTIVE, snapshot.state)
        assertEquals(1L, snapshot.recoveries)
        assertEquals(2L, snapshot.acceptedFrames)
    }

    @Test
    fun resetReturnsToIdleAndClearsCounters() {
        val tracker = TrackingHealthTracker()
        tracker.onFrameAccepted(100L)
        tracker.tick(700L)
        tracker.onInputCleared(700L)

        val snapshot = tracker.reset()
        assertEquals(TrackingHealthTracker.State.IDLE, snapshot.state)
        assertEquals(0L, snapshot.acceptedFrames)
        assertEquals(0L, snapshot.staleTransitions)
        assertEquals(0L, snapshot.lastClearedAtMs)
    }
}
