package com.noyclic.questphonexr.tracking

/** Copy-ready, monotonic tracking health diagnostics. */
internal object TrackingHealthDiagnostics {
    data class Summary(
        val state: TrackingHealthTracker.State,
        val acceptedFrames: Long,
        val gapCount: Long,
        val staleTransitions: Long,
        val recoveries: Long,
        val lastAcceptedAtMs: Long,
        val lastClearedAtMs: Long
    )

    enum class Verdict { IDLE, HEALTHY, DEGRADED, STALE, RECOVERED }

    fun summarize(snapshot: TrackingHealthTracker.Snapshot): Summary = Summary(
        snapshot.state, snapshot.acceptedFrames, snapshot.gapCount,
        snapshot.staleTransitions, snapshot.recoveries,
        snapshot.lastAcceptedAtMs, snapshot.lastClearedAtMs
    )

    fun verdict(snapshot: TrackingHealthTracker.Snapshot): Verdict = when {
        snapshot.state == TrackingHealthTracker.State.IDLE -> Verdict.IDLE
        snapshot.state == TrackingHealthTracker.State.STALE -> Verdict.STALE
        snapshot.recoveries > 0L && snapshot.state == TrackingHealthTracker.State.ACTIVE -> Verdict.RECOVERED
        snapshot.state == TrackingHealthTracker.State.GRACE -> Verdict.DEGRADED
        else -> Verdict.HEALTHY
    }

    fun format(snapshot: TrackingHealthTracker.Snapshot): String {
        val summary = summarize(snapshot)
        return buildString {
            appendLine("tracking_health_state=" + summary.state.name)
            appendLine("tracking_health_verdict=" + verdict(snapshot).name.lowercase())
            appendLine("tracking_health_accepted_frames=" + summary.acceptedFrames)
            appendLine("tracking_health_gap_count=" + summary.gapCount)
            appendLine("tracking_health_stale_transitions=" + summary.staleTransitions)
            appendLine("tracking_health_recoveries=" + summary.recoveries)
            appendLine("tracking_health_last_accepted_ms=" + summary.lastAcceptedAtMs)
            appendLine("tracking_health_last_cleared_ms=" + summary.lastClearedAtMs)
        }
    }
}
