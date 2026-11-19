package com.noyclic.questphonexr.tracking

/**
 * Small, monotonic health state machine shared by camera tracking and EndXR input.
 *
 * It distinguishes a healthy stream from a short inference gap and a real stale-input
 * condition without inventing tracking data. Callers provide timestamps from the same
 * monotonic clock and explicitly acknowledge when a frame was accepted or when input was
 * cleared.
 */
internal class TrackingHealthTracker(
    private val gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS
) {
    enum class State { IDLE, ACTIVE, GRACE, STALE }

    data class Snapshot(
        val state: State,
        val acceptedFrames: Long,
        val gapCount: Long,
        val staleTransitions: Long,
        val recoveries: Long,
        val lastAcceptedAtMs: Long,
        val lastClearedAtMs: Long
    )

    private var state = State.IDLE
    private var acceptedFrames = 0L
    private var gapCount = 0L
    private var staleTransitions = 0L
    private var recoveries = 0L
    private var lastAcceptedAtMs = 0L
    private var lastClearedAtMs = 0L

    init {
        require(gracePeriodMs >= 0L)
        require(staleAfterMs > gracePeriodMs)
    }

    fun onFrameAccepted(nowMs: Long): Snapshot {
        require(nowMs >= 0L)
        if (state == State.STALE) recoveries++
        acceptedFrames++
        lastAcceptedAtMs = nowMs
        state = State.ACTIVE
        return snapshot()
    }

    /**
     * Advances the state without fabricating a frame.
     *
     * @return true only on the transition into STALE, allowing the bridge to clear stale
     * input exactly once.
     */
    fun tick(nowMs: Long): Boolean {
        require(nowMs >= 0L)
        if (lastAcceptedAtMs == 0L) {
            state = State.IDLE
            return false
        }
        val age = (nowMs - lastAcceptedAtMs).coerceAtLeast(0L)
        return when {
            age <= gracePeriodMs -> {
                state = State.ACTIVE
                false
            }
            age < staleAfterMs -> {
                gapCount++
                state = State.GRACE
                false
            }
            state != State.STALE -> {
                state = State.STALE
                staleTransitions++
                true
            }
            else -> false
        }
    }

    fun onInputCleared(nowMs: Long): Snapshot {
        require(nowMs >= 0L)
        lastClearedAtMs = nowMs
        return snapshot()
    }

    fun reset(): Snapshot {
        state = State.IDLE
        acceptedFrames = 0L
        gapCount = 0L
        staleTransitions = 0L
        recoveries = 0L
        lastAcceptedAtMs = 0L
        lastClearedAtMs = 0L
        return snapshot()
    }

    fun snapshot(): Snapshot = Snapshot(
        state = state,
        acceptedFrames = acceptedFrames,
        gapCount = gapCount,
        staleTransitions = staleTransitions,
        recoveries = recoveries,
        lastAcceptedAtMs = lastAcceptedAtMs,
        lastClearedAtMs = lastClearedAtMs
    )

    private companion object {
        const val DEFAULT_GRACE_PERIOD_MS = 120L
        const val DEFAULT_STALE_AFTER_MS = 450L
    }
}
