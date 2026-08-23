package com.looptimer.domain

/** Which half of a loop the timer is in. */
enum class Phase {
    WORK,
    BREAK;

    fun other(): Phase = if (this == WORK) BREAK else WORK
}

/** What the timer is doing right now. */
enum class RunState {
    /** Not started, or reset. */
    IDLE,

    /** Counting down. */
    RUNNING,

    /** Started but currently held. */
    PAUSED,

    /**
     * A phase ended and the next one is queued but waiting for the user, because
     * [TimerConfig.autoStart] is off.
     */
    AWAITING_CONFIRMATION,

    /** All configured loops finished. */
    COMPLETED
}

/**
 * An immutable snapshot of timer progress.
 *
 * [currentLoop] is validated here rather than at the call sites. The old code advanced the loop
 * counter in three separate places, one of which incremented unconditionally and could display
 * "round 5 / 4"; making the invariant part of the type means that bug cannot reappear silently.
 */
data class TimerState(
    val phase: Phase = Phase.WORK,
    val currentLoop: Int = 1,
    val remainingSeconds: Int = 0,
    val runState: RunState = RunState.IDLE,
    val totalLoops: Int = 1
) {
    init {
        require(totalLoops in TimeRanges.LOOPS) { "totalLoops out of range: $totalLoops" }
        require(currentLoop in 1..totalLoops) {
            "currentLoop must be within 1..$totalLoops, was $currentLoop"
        }
        require(remainingSeconds >= 0) { "remainingSeconds must not be negative: $remainingSeconds" }
    }

    val isRunning: Boolean get() = runState == RunState.RUNNING
    val isFinished: Boolean get() = runState == RunState.COMPLETED

    /** True when the timer is holding for the user to confirm the next phase. */
    val isAwaitingConfirmation: Boolean get() = runState == RunState.AWAITING_CONFIRMATION

    /** True when pressing start is meaningful. */
    val canStart: Boolean
        get() = remainingSeconds > 0 && runState != RunState.RUNNING

    val isLastLoop: Boolean get() = currentLoop >= totalLoops

    companion object {
        /** The initial state for [config]: first work phase, full duration, not started. */
        fun initial(config: TimerConfig): TimerState = TimerState(
            phase = Phase.WORK,
            currentLoop = 1,
            remainingSeconds = config.workTotalSeconds,
            runState = RunState.IDLE,
            totalLoops = config.loops
        )
    }
}
