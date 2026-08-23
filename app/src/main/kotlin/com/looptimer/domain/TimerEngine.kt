package com.looptimer.domain

/**
 * The effects a caller should perform after a state transition.
 *
 * The engine stays a pure function: it decides *what* should happen and the service layer
 * decides *how*. This is what makes the whole state machine unit-testable without Android.
 */
data class TimerEffects(
    /** Ring the alarm, using this setting. Null means "do not ring". */
    val alarm: AlarmSetting? = null,
    /** Post an end-of-phase notification with this content. Null means "do not notify". */
    val notification: PhaseEndNotification? = null
)

/** Describes the phase that just ended, so the UI layer can resolve display strings. */
data class PhaseEndNotification(
    val endedPhase: Phase,
    /** True when the timer is waiting for the user to start the next phase. */
    val awaitingConfirmation: Boolean,
    /** True when this was the final phase of the final loop. */
    val allLoopsComplete: Boolean
)

/** A new state plus the side effects that accompany reaching it. */
data class Transition(
    val state: TimerState,
    val effects: TimerEffects = TimerEffects()
)

/**
 * The pure timer state machine.
 *
 * Every phase change in the app funnels through here. Previously this logic existed in three
 * separate copies (the countdown coroutine, the skip button, and the notification-resume path)
 * which had diverged and disagreed about loop counting; consolidating them fixed the
 * "round 5 / 4" overflow and the duplicated resume branches.
 *
 * All functions are total and side-effect free, so they can be exhaustively tested.
 */
object TimerEngine {

    /** Fresh state for [config]. */
    fun reset(config: TimerConfig): TimerState = TimerState.initial(config)

    /** Begins or resumes the countdown, if there is anything to count. */
    fun start(state: TimerState, config: TimerConfig): TimerState = when {
        state.remainingSeconds > 0 ->
            state.copy(runState = RunState.RUNNING)
        // Nothing left on the clock: treat start as "begin the next phase".
        else -> advance(state, config).state.let {
            if (it.remainingSeconds > 0) it.copy(runState = RunState.RUNNING) else it
        }
    }

    fun pause(state: TimerState): TimerState =
        if (state.isRunning) state.copy(runState = RunState.PAUSED) else state

    /**
     * Applies [elapsedSeconds] of countdown.
     *
     * Returns a transition whose state may still be mid-phase, or which has rolled over into
     * the next phase (carrying the appropriate effects). Only ever advances one phase boundary
     * per call; callers driving large jumps should loop.
     */
    fun tick(state: TimerState, config: TimerConfig, elapsedSeconds: Int): Transition {
        if (!state.isRunning || elapsedSeconds <= 0) return Transition(state)

        val remaining = state.remainingSeconds - elapsedSeconds
        if (remaining > 0) {
            return Transition(state.copy(remainingSeconds = remaining))
        }
        return advance(state, config)
    }

    /**
     * Advances past the current phase, as if it had just elapsed.
     *
     * This is the single definition of "what comes next" and backs both the natural countdown
     * rollover and the manual skip action.
     */
    fun advance(state: TimerState, config: TimerConfig): Transition {
        val endedPhase = state.phase
        val next = nextPosition(state, config)

        if (next == null) {
            // The whole session is over.
            val completed = state.copy(
                phase = Phase.WORK,
                remainingSeconds = 0,
                runState = RunState.COMPLETED
            )
            return Transition(
                state = completed,
                effects = TimerEffects(
                    alarm = config.alarm.takeIf { it.isEnabled },
                    notification = PhaseEndNotification(
                        endedPhase = endedPhase,
                        awaitingConfirmation = false,
                        allLoopsComplete = true
                    )
                )
            )
        }

        val (nextPhase, nextLoop) = next
        val duration = config.durationOf(nextPhase)
        val runState = if (config.autoStart) RunState.RUNNING else RunState.AWAITING_CONFIRMATION

        return Transition(
            state = state.copy(
                phase = nextPhase,
                currentLoop = nextLoop,
                remainingSeconds = duration,
                runState = runState
            ),
            effects = TimerEffects(
                alarm = config.alarm.takeIf { it.isEnabled },
                notification = PhaseEndNotification(
                    endedPhase = endedPhase,
                    awaitingConfirmation = !config.autoStart,
                    allLoopsComplete = false
                )
            )
        )
    }

    /**
     * Skips the remainder of the current phase. Identical progression rules to [advance];
     * the old implementation had its own copy that incremented the loop counter without
     * checking the upper bound.
     */
    fun skip(state: TimerState, config: TimerConfig): Transition = advance(state, config)

    /**
     * Where the timer goes after the current phase, or null when the session is complete.
     *
     * Breaks are skipped entirely when the configured break duration is zero, and the break
     * after the final work phase is never played, so a session always ends on work.
     */
    private fun nextPosition(state: TimerState, config: TimerConfig): Pair<Phase, Int>? =
        when (state.phase) {
            Phase.WORK -> when {
                // Final work phase: session over.
                state.isLastLoop -> null
                // No break configured: go straight to the next loop's work phase.
                !config.hasBreak -> Phase.WORK to state.currentLoop + 1
                else -> Phase.BREAK to state.currentLoop
            }

            Phase.BREAK -> when {
                // A break is always followed by the next loop's work phase. Reaching a break
                // on the last loop shouldn't happen (WORK on the last loop ends the session),
                // but guard anyway rather than letting currentLoop exceed totalLoops.
                state.isLastLoop -> null
                else -> Phase.WORK to state.currentLoop + 1
            }
        }
}
