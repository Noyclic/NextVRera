package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class EndXrTrackingInputBridgeTest {
    @Test
    fun clearFramesIsSafeWithAndroidClockStubsAndResetsHealth() {
        EndXrTrackingInputBridge.clearFrames()

        assertEquals(
            com.noyclic.questphonexr.tracking.TrackingHealthTracker.State.IDLE,
            EndXrTrackingInputBridge.healthSnapshot().state
        )
    }
}
