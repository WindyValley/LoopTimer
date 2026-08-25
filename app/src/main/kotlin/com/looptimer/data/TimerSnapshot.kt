package com.looptimer.data

import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerState

/**
 * A persisted snapshot of a run in progress.
 *
 * Needed because the timer's authoritative state lives in memory while the phase deadline lives in
 * `AlarmManager`, which survives process death. Without this snapshot the service rebuilt itself
 * with `TimerEngine.reset(config)` and jumped back to the full duration, even though the alarm was
 * still counting down to the original boundary - the two disagreed.
 *
 * The deadline is stored on the **monotonic** timebase (`elapsedRealtime`) for arithmetic and
 * additionally as wall-clock, so a reboot (which resets the monotonic clock) can be detected and
 * the snapshot discarded rather than misinterpreted.
 */
data class TimerSnapshot(
    val phase: Phase,
    val currentLoop: Int,
    val totalLoops: Int,
    val runState: RunState,
    /** Remaining seconds; authoritative when not running. */
    val remainingSeconds: Int,
    /** Monotonic instant the phase ends, or null when not counting down. */
    val deadlineElapsedMs: Long?,
    /** Wall-clock instant the phase ends, used to detect a reboot. */
    val deadlineWallClockMs: Long?,
    /** Wall-clock time the snapshot was written. */
    val savedAtWallClockMs: Long
) {
    fun toState(): TimerState = TimerState(
        phase = phase,
        currentLoop = currentLoop,
        remainingSeconds = remainingSeconds,
        runState = runState,
        totalLoops = totalLoops
    )

    companion object {
        fun of(
            state: TimerState,
            deadlineElapsedMs: Long?,
            nowElapsedMs: Long,
            nowWallClockMs: Long
        ): TimerSnapshot = TimerSnapshot(
            phase = state.phase,
            currentLoop = state.currentLoop,
            totalLoops = state.totalLoops,
            runState = state.runState,
            remainingSeconds = state.remainingSeconds,
            deadlineElapsedMs = deadlineElapsedMs,
            deadlineWallClockMs = deadlineElapsedMs?.let {
                nowWallClockMs + (it - nowElapsedMs)
            },
            savedAtWallClockMs = nowWallClockMs
        )
    }
}
