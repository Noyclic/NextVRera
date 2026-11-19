package com.noyclic.questphonexr.runtime

/**
 * Small deterministic state machine for the shared hand-tracking -> EndXR path.
 *
 * It distinguishes a brief inference gap from a real loss of tracking:
 * - fresh frames keep the session ACTIVE;
 * - a gap up to [gracePeriodMs] keeps the last published input alive;
 * - after the grace period, the bridge must clear EndXR input exactly once;
 * - the first fresh frame after loss returns to REACQUIRED, allowing diagnostics/UI
 *   to report recovery without inventing a second provider-specific state machine.
 */
internal class TrackingSessionWatchdog(
    private val gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS
) {
    enum class State { IDLE, ACTIVE, GRACE, LOST, REACQUIRED }

    data class Decision(
        val state: State,
        val shouldClearInput: Boolean,
        val shouldPublish: Boolean,
        val ageMs: Long?
    )

    private var state = State.IDLE
    private var lastFrameAtMs: Long? = null
    private var clearedForLoss = false

    init {
        require(gracePeriodMs in 0L..5_000L)
    }

    fun onFreshFrame(nowMs: Long): Decision {
        require(nowMs >= 0L)
        val previous = state
        val age = lastFrameAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
        lastFrameAtMs = nowMs
        clearedForLoss = false
        state = if (previous == State.LOST) State.REACQUIRED else State.ACTIVE
        return Decision(
            state = state,
            shouldClearInput = false,
            shouldPublish = true,
            ageMs = age
        )
    }

    fun onTick(nowMs: Long): Decision {
        require(nowMs >= 0L)
        val last = lastFrameAtMs ?: return Decision(
            state = state,
            shouldClearInput = false,
            shouldPublish = false,
            ageMs = null
        )
        val age = (nowMs - last).coerceAtLeast(0L)
        if (age <= gracePeriodMs) {
            state = State.GRACE
            return Decision(state, false, false, age)
        }
        val shouldClear = !clearedForLoss
        clearedForLoss = true
        state = State.LOST
        return Decision(state, shouldClear, false, age)
    }

    fun state(): State = state

    fun reset() {
        state = State.IDLE
        lastFrameAtMs = null
        clearedForLoss = false
    }

    private companion object {
        const val DEFAULT_GRACE_PERIOD_MS = 120L
    }
}
