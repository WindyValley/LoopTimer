package com.looptimer.service

import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.PhaseEndNotification
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerEngine
import com.looptimer.domain.TimerState
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 * Two design points matter here:
 *
 * 1. **Deadline-based timing.** The countdown stores the monotonic instant the phase ends and
 *    derives the remaining seconds from the clock. The previous implementation decremented a
 *    counter once per `delay(1000)`, so every scheduling hiccup accumulated into permanent drift
 *    and the timer stalled entirely while the process was frozen.
 *
 * 2. **Ownership.** State lives here, not in a composable's `remember`. The countdown is driven
 *    by an injected scope tied to the host's lifetime, so it survives configuration changes and
 *    keeps running while the UI is gone - which is what the foreground notification claims.
 *
 * The class is deliberately free of Android dependencies so it can be tested with a virtual clock.
 */
class TimerController(
    private val scope: CoroutineScope,
    private val clock: ElapsedClock,
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
     * (re)composition, not just the transition. The old code polled a global singleton every
     * 100 ms to approximate this.
     */
    private val _alarmRinging = MutableStateFlow(false)
    val alarmRinging: StateFlow<Boolean> = _alarmRinging.asStateFlow()

    /** Monotonic instant at which the current phase ends; null when not counting down. */
    private var deadlineMs: Long? = null
    private var tickJob: Job? = null
    private var alarmJob: Job? = null

    /**
     * Replaces the configuration.
     *
     * A running timer keeps its progress; an idle one re-derives its duration from the new config
     * so that editing the work length is immediately reflected on screen.
     */
    fun updateConfig(config: TimerConfig) {
        val previous = _config.value
        _config.value = config
        if (previous == config) return

        val current = _state.value
        if (current.runState == RunState.IDLE || current.runState == RunState.COMPLETED) {
            stopTicking()
            _state.value = TimerEngine.reset(config)
        } else {
            // Keep counting, but honour a changed loop count.
            val clampedLoop = current.currentLoop.coerceAtMost(config.loops)
            _state.value = current.copy(totalLoops = config.loops, currentLoop = clampedLoop)
        }
    }

    fun start() {
        val config = _config.value
        val started = TimerEngine.start(_state.value, config)
        if (!started.isRunning) {
            _state.value = started
            return
        }
        _alarmRinging.value = false
        _events.tryEmit(TimerEvent.StopAlarm)
        _state.value = started
        armDeadline(started.remainingSeconds)
        launchTicker()
    }

    fun pause() {
        val paused = TimerEngine.pause(_state.value)
        if (paused === _state.value) return
        // Freeze the derived remaining time before dropping the deadline.
        _state.value = paused.copy(remainingSeconds = remainingFromDeadline(paused.remainingSeconds))
        stopTicking()
    }

    fun toggle() {
        if (_state.value.isRunning) pause() else start()
    }

    fun reset() {
        stopTicking()
        _alarmRinging.value = false
        _events.tryEmit(TimerEvent.StopAlarm)
        _state.value = TimerEngine.reset(_config.value)
    }

    fun skip() {
        val config = _config.value
        stopTicking()
        applyTransition(TimerEngine.skip(_state.value, config))
    }

    /** Stops the alarm without otherwise disturbing the timer. */
    fun stopAlarm() {
        alarmJob?.cancel()
        alarmJob = null
        _alarmRinging.value = false
        _events.tryEmit(TimerEvent.StopAlarm)
    }

    /** Releases the ticker. The injected scope is owned by the host, so it is not cancelled here. */
    fun dispose() {
        stopTicking()
        alarmJob?.cancel()
        alarmJob = null
    }

    // ------------------------------------------------------------------ internals

    private fun armDeadline(seconds: Int) {
        deadlineMs = clock.elapsedRealtimeMs() + seconds * MILLIS_PER_SECOND
    }

    /** Remaining whole seconds implied by the deadline, rounded up so the UI shows a full second. */
    private fun remainingFromDeadline(fallback: Int): Int {
        val deadline = deadlineMs ?: return fallback
        val remainingMs = deadline - clock.elapsedRealtimeMs()
        if (remainingMs <= 0) return 0
        return ceilToSeconds(remainingMs)
    }

    private fun ceilToSeconds(millis: Long): Int =
        ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        deadlineMs = null
    }

    private fun launchTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            // Loops until the deadline is cleared or the timer stops running; each iteration
            // either publishes the derived remaining time or crosses a phase boundary.
            while (isActive && _state.value.isRunning) {
                val deadline = deadlineMs ?: break
                val remainingMs = deadline - clock.elapsedRealtimeMs()

                if (remainingMs <= 0) {
                    // Crossing a boundary re-arms the deadline for the next phase (or stops).
                    onPhaseElapsed()
                } else {
                    // Publish the derived value, then sleep only until the next second boundary
                    // so the display flips promptly without busy-waiting.
                    _state.value = _state.value.copy(remainingSeconds = ceilToSeconds(remainingMs))
                    val untilNextBoundary = remainingMs % MILLIS_PER_SECOND
                    delay(
                        max(
                            1L,
                            if (untilNextBoundary == 0L) MILLIS_PER_SECOND else untilNextBoundary
                        )
                    )
                }
            }
        }
    }

    private fun onPhaseElapsed() {
        val config = _config.value
        // Force the engine to see an exhausted clock, then advance exactly one boundary.
        val exhausted = _state.value.copy(remainingSeconds = 0)
        applyTransition(TimerEngine.advance(exhausted, config))
    }

    private fun applyTransition(transition: com.looptimer.domain.Transition) {
        _state.value = transition.state

        transition.effects.alarm?.let { raiseAlarm(it) }
        transition.effects.notification?.let { _events.tryEmit(TimerEvent.PhaseEnded(it)) }

        if (transition.state.isRunning && transition.state.remainingSeconds > 0) {
            armDeadline(transition.state.remainingSeconds)
            launchTicker()
        } else {
            stopTicking()
        }
    }

    /**
     * Raises the alarm and, for a fixed-duration setting, schedules its stop.
     *
     * The auto-stop lives here rather than in the alarm player so it participates in structured
     * concurrency; the previous implementation used a bare interruptible `Thread`.
     */
    private fun raiseAlarm(setting: AlarmSetting) {
        alarmJob?.cancel()
        _alarmRinging.value = true
        _events.tryEmit(TimerEvent.StartAlarm(setting))

        if (setting is AlarmSetting.Fixed) {
            alarmJob = scope.launch {
                delay(setting.seconds * MILLIS_PER_SECOND)
                _alarmRinging.value = false
                _events.tryEmit(TimerEvent.StopAlarm)
            }
        } else {
            alarmJob = null
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
