package com.noyclic.questphonexr.runtime

import android.os.SystemClock
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackingProviderAvailability
import com.noyclic.questphonexr.tracking.TrackingProviderId
import com.noyclic.questphonexr.tracking.TrackingProviderManager
import com.noyclic.questphonexr.tracking.TrackingProviderManagerSnapshot

/**
 * Common entrance from every tracking implementation into the existing EndXR input path.
 *
 * Provider-specific code stops at HandTrackingFrame. This bridge owns arbitration and then
 * sends only the selected fresh frame through the shared gesture/pose/joint encoder.
 */
internal object EndXrTrackingInputBridge {
    private val providerManager = TrackingProviderManager()
    private val healthTracker = com.noyclic.questphonexr.tracking.TrackingHealthTracker()

    fun publish(
        providerId: TrackingProviderId,
        frame: HandTrackingFrame,
        // MediaPipe currently does not expose its detection confidence in HandTrackingFrame.
        // Use a neutral baseline instead of awarding it a fabricated maximum score.
        qualityScore: Float = 0.5f
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val selected = providerManager.publishFrame(
            providerId = providerId,
            frame = frame,
            qualityScore = qualityScore,
            receivedAtUptimeMs = now
        ) ?: return false

        healthTracker.onFrameAccepted(now)
        EndXrHandTrackingDiagnostics.onFrame(selected.frame, selected.providerId.wireId)
        val encoded = EndXrHandInputEncoder.encode(selected.frame)
        EndXrHandTrackingDiagnostics.updateNaturalGestures(
            encoded.leftGesture,
            encoded.rightGesture
        )
        EndXrInputSnapshotStore.publishTrackingFrame(
            leftRow = encoded.leftRow,
            rightRow = encoded.rightRow,
            leftPose = encoded.leftPose,
            rightPose = encoded.rightPose,
            leftJoints = encoded.leftJoints,
            rightJoints = encoded.rightJoints
        )
        return true
    }

    fun selectProvider(providerId: TrackingProviderId): Boolean {
        val selected = providerManager.selectProvider(providerId)
        if (selected) {
            EndXrInputSnapshotStore.clear()
            healthTracker.reset()
        }
        return selected
    }

    fun setAutoMode() {
        providerManager.setAutoMode()
        EndXrInputSnapshotStore.clear()
        healthTracker.reset()
    }

    fun updateProviderAvailability(
        providerId: TrackingProviderId,
        availability: TrackingProviderAvailability
    ): Boolean {
        val updated = providerManager.updateAvailability(providerId, availability)
        if (updated) EndXrInputSnapshotStore.clear()
        return updated
    }

    /** Advances freshness state; returns true when stale input was cleared on this tick. */
    fun tick(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (!healthTracker.tick(nowUptimeMs)) return false
        EndXrInputSnapshotStore.clear()
        healthTracker.onInputCleared(nowUptimeMs)
        providerManager.clearFrames()
        return true
    }

    fun healthSnapshot(): com.noyclic.questphonexr.tracking.TrackingHealthTracker.Snapshot =
        healthTracker.snapshot()

    fun clearFrames() {
        providerManager.clearFrames()
        val now = runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L)
        EndXrInputSnapshotStore.clear()
        healthTracker.onInputCleared(now)
    }

    fun diagnosticSnapshot(): TrackingProviderManagerSnapshot {
        // Android's mocked SystemClock throws in local JVM tests. Diagnostics must
        // remain readable there, while production continues to use receiver uptime.
        val nowUptimeMs = runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L)
        return providerManager.snapshot(nowUptimeMs)
    }
}
