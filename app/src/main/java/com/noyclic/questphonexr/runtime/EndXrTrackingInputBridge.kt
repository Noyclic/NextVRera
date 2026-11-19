package com.noyclic.questphonexr.runtime

import android.os.SystemClock
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackingProviderAvailability
import com.noyclic.questphonexr.tracking.TrackingProviderId
import com.noyclic.questphonexr.tracking.TrackingProviderManager
import com.noyclic.questphonexr.tracking.TrackingProviderManagerSnapshot
import com.noyclic.questphonexr.tracking.TrackingHealthTracker

/**
 * Common entrance from every tracking implementation into the existing EndXR input path.
 *
 * Provider-specific code stops at HandTrackingFrame. This bridge owns arbitration and then
 * sends only the selected fresh frame through the shared gesture/pose/joint encoder.
 */
internal object EndXrTrackingInputBridge {
    private val providerManager = TrackingProviderManager()
    private val healthTracker = TrackingHealthTracker()

    @Synchronized
    fun publish(
        providerId: TrackingProviderId,
        frame: HandTrackingFrame,
        // MediaPipe currently does not expose its detection confidence in HandTrackingFrame.
        // Use a neutral baseline instead of awarding it a fabricated maximum score.
        qualityScore: Float = 0.5f
    ): Boolean {
        val now = runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L)
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

    @Synchronized
    fun selectProvider(providerId: TrackingProviderId): Boolean {
        val selected = providerManager.selectProvider(providerId)
        if (selected) {
            clearInputSnapshot()
            healthTracker.reset()
        }
        return selected
    }

    @Synchronized
    fun setAutoMode() {
        providerManager.setAutoMode()
        clearInputSnapshot()
        healthTracker.reset()
    }

    @Synchronized
    fun updateProviderAvailability(
        providerId: TrackingProviderId,
        availability: TrackingProviderAvailability
    ): Boolean {
        val updated = providerManager.updateAvailability(providerId, availability)
        if (updated) {
            clearInputSnapshot()
            healthTracker.reset()
        }
        return updated
    }

    /** Advances freshness state; returns true when stale input was cleared on this tick. */
    @Synchronized
    fun tick(nowUptimeMs: Long = runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L)): Boolean {
        if (!healthTracker.tick(nowUptimeMs)) return false
        clearInputSnapshot()
        healthTracker.onInputCleared(nowUptimeMs)
        providerManager.clearFrames()
        return true
    }

    @Synchronized
    fun healthSnapshot(): com.noyclic.questphonexr.tracking.TrackingHealthTracker.Snapshot =
        healthTracker.snapshot()

    @Synchronized
    fun clearFrames() {
        providerManager.clearFrames()
        clearInputSnapshot()
        healthTracker.reset()
    }

    private fun clearInputSnapshot() {
        // The Android SDK stub throws in local JVM tests; a zero timestamp is safe there.
        // On-device this always uses Android's monotonic elapsed-realtime clock.
        val nowElapsedRealtimeNanos =
            runCatching { SystemClock.elapsedRealtimeNanos() }.getOrDefault(0L)
        EndXrInputSnapshotStore.clear(nowElapsedRealtimeNanos)
    }

    @Synchronized
    fun diagnosticSnapshot(): TrackingProviderManagerSnapshot {
        // Android's mocked SystemClock throws in local JVM tests. Diagnostics must
        // remain readable there, while production continues to use receiver uptime.
        val nowUptimeMs = runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L)
        return providerManager.snapshot(nowUptimeMs)
    }
}
