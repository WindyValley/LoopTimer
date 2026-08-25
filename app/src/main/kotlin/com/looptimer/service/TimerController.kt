package com.looptimer.service

import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.PhaseEndNotification
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerEngine
import com.looptimer.domain.TimerState
import com.looptimer.domain.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A side effect the host (service) must carry out; the controller never touches Android APIs. */
sealed interface TimerEvent {
    /** Start ringing with [setting]. */
    data class StartAlarm(val setting: AlarmSetting) : TimerEvent

    /** Stop any ringing alarm. */
    data object StopAlarm : TimerEvent

    /** Post an end-of-phase notification. */
    data class PhaseEnded(val info: PhaseEndNotification) : TimerEvent
}

/**
 * Owns the authoritative timer state.
 *
 * Three design points matter here:
 *
 * 1. **Deadline-based, not tick-based.** The controller stores the monotonic instant the current
 *    phase ends and derives remaining time from the clock on demand. The original implementation
 *    decremented a counter once per `delay(1000)`, so scheduling jitter accumulated into permanent
 *    drift and the timer stalled entirely while the process was frozen.
 *
 * 2. **No per-second work.** Reaching a boundary is delegated to a [DeadlineScheduler]
 *    (`AlarmManager` in production), and the visible countdown is rendered by the notification's
 *    own chronometer. Between boundaries the app runs no code at all, which is both cheaper and
 *    more robust than keeping a process alive to count.
 *
 * 3. **Ownership.** State lives here, not in a composable's `remember`, so it survives
 *    configuration changes and outlives the UI - which is what the notification claims.
 *
 * Free of Android dependencies, so it is testable with a virtual clock and a fake scheduler.
 */
class TimerController(
    private val scope: CoroutineScope,
    private val clock: ElapsedClock,
    private val scheduler: DeadlineScheduler = DeadlineScheduler.NONE,
    initialConfig: TimerConfig = TimerConfig()
) {
    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<TimerConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow(TimerEngine.reset(initialConfig))
    val state: StateFlow<TimerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TimerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TimerEvent> = _events.asSharedFlow()

    /**
     * Whether the alarm is currently ringing, as observable state.
     *
     * Exposed separately from [events] because the UI needs the *current* value on
     * (re)composition, not just the transition. The old code polled a global singleton
     * every 100 ms to approximate this.
     */
    private val _alarmRinging = MutableStateFlow(false)
    val alarmRinging: StateFlow<Boolean> = _alarmRinging.asStateFlow()

    /**
     * Monotonic instant at which the running phase ends, or null when not counting down.
     *
     * Published so the host can hand it to the notification chronometer, which then counts down
     * without any further involvement from us.
     */
    private val _deadlineElapsedMs = MutableStateFlow<Long?>(null)
    val deadlineElapsedMs: StateFlow<Long?> = _deadlineElapsedMs.asStateFlow()

    private var alarmJob: Job? = null

    /**
     * Restores a run that was interrupted by process death.
     *
     * The remaining time is recomputed from the persisted deadline rather than trusting the stored
     * seconds, so the restored state agrees with the `AlarmManager` wake-up that is still pending.
     * A snapshot whose deadline has already passed is advanced immediately; one that cannot be
     * trusted (e.g. after a reboot reset the monotonic clock) is ignored by the caller.
     */
    fun restore(state: TimerState, deadlineElapsedMs: Long?) {
        _state.value = state
        _deadlineElapsedMs.value = deadlineElapsedMs

        if (!state.isRunning || deadlineElapsedMs == null) {
            // Nothing should be counting; make sure no stale wake-up survives.
            if (deadlineElapsedMs == null) scheduler.cancel()
            return
        }

        val remaining = remainingSeconds()
        if (remaining <= 0) {
            // The boundary elapsed while we were dead; catch up now.
            onDeadlineReached()
        } else {
            _state.value = state.copy(remainingSeconds = remaining)
            // Re-arm defensively: the pending alarm may have been cleared with the process.
            scheduler.schedule(deadlineElapsedMs)
        }
    }

    /**
     * Replaces the configuration.
     *
     * A running timer keeps its progress; an idle one re-derives its duration from the new config
     * so that editing the work length is immediately reflected on screen.
     */
    fun updateConfig(config: TimerConfig) {
        val previous = _config.value
        if (previous == config) return
        _config.value = config

        val current = _state.value
        if (current.runState == RunState.IDLE || current.runState == RunState.COMPLETED) {
            clearDeadline()
            _state.value = TimerEngine.reset(config)
        } else {
            // Keep counting, but honour a changed loop count.
            _state.value = current.copy(
                totalLoops = config.loops,
                currentLoop = current.currentLoop.coerceAtMost(config.loops)
            )
        }
    }

    fun start() {
        val started = TimerEngine.start(_state.value, _config.value)
        stopAlarmInternal()
        _state.value = started
        if (started.isRunning && started.remainingSeconds > 0) {
            armDeadline(started.remainingSeconds)
        } else {
            clearDeadline()
        }
    }

    fun pause() {
        val current = _state.value
        if (!current.isRunning) return
        // Freeze the derived remaining time before discarding the deadline.
        val remaining = remainingSeconds()
        clearDeadline()
        _state.value = current.copy(
            runState = RunState.PAUSED,
            remainingSeconds = remaining
        )
    }

    fun toggle() {
        if (_state.value.isRunning) pause() else start()
    }

    fun reset() {
        clearDeadline()
        stopAlarmInternal()
        _state.value = TimerEngine.reset(_config.value)
    }

    fun skip() {
        applyTransition(TimerEngine.skip(_state.value, _config.value))
    }

    /**
     * Advances past the phase whose deadline has just elapsed.
     *
     * Called by the host when the scheduler fires. Idempotent with respect to spurious wake-ups:
     * if time remains, the state is simply refreshed instead of advancing.
     */
    fun onDeadlineReached() {
        val current = _state.value
        if (!current.isRunning) return

        val remaining = remainingSeconds()
        if (remaining > 0) {
            // Woken early; re-arm for the time that is actually left.
            _state.value = current.copy(remainingSeconds = remaining)
            armDeadline(remaining)
            return
        }
        applyTransition(TimerEngine.advance(current.copy(remainingSeconds = 0), _config.value))
    }

    /** Recomputes the visible remaining time from the clock, e.g. when the UI reappears. */
    fun refresh() {
        val current = _state.value
        if (!current.isRunning) return
        _state.value = current.copy(remainingSeconds = remainingSeconds())
    }

    /** Stops the alarm without otherwise disturbing the timer. */
    fun stopAlarm() {
        stopAlarmInternal()
    }

    /** Releases scheduling resources. The injected scope is owned by the host. */
    fun dispose() {
        alarmJob?.cancel()
        alarmJob = null
    }

    // ------------------------------------------------------------------ internals

    /** Remaining whole seconds, rounded up so the display shows a full final second. */
    private fun remainingSeconds(): Int {
        val deadline = _deadlineElapsedMs.value ?: return _state.value.remainingSeconds
        val remainingMs = deadline - clock.elapsedRealtimeMs()
        if (remainingMs <= 0) return 0
        return ((remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    }

    private fun armDeadline(seconds: Int) {
        val at = clock.elapsedRealtimeMs() + seconds * MILLIS_PER_SECOND
        _deadlineElapsedMs.value = at
        scheduler.schedule(at)
    }

    private fun clearDeadline() {
        _deadlineElapsedMs.value = null
        scheduler.cancel()
    }

    private fun applyTransition(transition: Transition) {
        _state.value = transition.state

        transition.effects.alarm?.let(::raiseAlarm)
        transition.effects.notification?.let { _events.tryEmit(TimerEvent.PhaseEnded(it)) }

        if (transition.state.isRunning && transition.state.remainingSeconds > 0) {
            armDeadline(transition.state.remainingSeconds)
        } else {
            clearDeadline()
        }
    }

    /**
     * Raises the alarm and, for a fixed-duration setting, schedules its stop.
     *
     * The auto-stop participates in structured concurrency; the original implementation used a
     * bare interruptible `Thread`. This is the one place a coroutine still waits on wall time,
     * and only while the alarm is audible.
     */
    private fun raiseAlarm(setting: AlarmSetting) {
        alarmJob?.cancel()
        _alarmRinging.value = true
        _events.tryEmit(TimerEvent.StartAlarm(setting))

        alarmJob = if (setting is AlarmSetting.Fixed) {
            scope.launch {
                delay(setting.seconds * MILLIS_PER_SECOND)
                stopAlarmInternal()
            }
        } else {
            null
        }
    }

    private fun stopAlarmInternal() {
        alarmJob?.cancel()
        alarmJob = null
        _alarmRinging.value = false
        _events.tryEmit(TimerEvent.StopAlarm)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
