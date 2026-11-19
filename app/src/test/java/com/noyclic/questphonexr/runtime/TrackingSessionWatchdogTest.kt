package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSessionWatchdogTest {
    @Test
    fun freshFrameAfterLoss_isMarkedAsReacquired() {
        val watchdog = TrackingSessionWatchdog(gracePeriodMs = 100L)

        watchdog.onFreshFrame(1_000L)
        assertEquals(TrackingSessionWatchdog.State.GRACE, watchdog.onTick(1_080L).state)
        assertTrue(watchdog.onTick(1_101L).shouldClearInput)

        val recovered = watchdog.onFreshFrame(1_120L)
        assertEquals(TrackingSessionWatchdog.State.REACQUIRED, recovered.state)
        assertTrue(recovered.shouldPublish)
        assertFalse(recovered.shouldClearInput)
    }

    @Test
    fun lossClearsInputOnlyOnce() {
        val watchdog = TrackingSessionWatchdog(gracePeriodMs = 0L)

        watchdog.onFreshFrame(500L)
        assertTrue(watchdog.onTick(501L).shouldClearInput)
        assertFalse(watchdog.onTick(502L).shouldClearInput)
        assertEquals(TrackingSessionWatchdog.State.LOST, watchdog.state())
    }

    @Test
    fun reset_returnsToIdle() {
        val watchdog = TrackingSessionWatchdog()
        watchdog.onFreshFrame(10L)
        watchdog.reset()
        assertEquals(TrackingSessionWatchdog.State.IDLE, watchdog.state())
        assertFalse(watchdog.onTick(10_000L).shouldClearInput)
    }
}
