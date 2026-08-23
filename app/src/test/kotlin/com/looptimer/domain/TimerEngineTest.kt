package com.looptimer.domain

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Behavioural coverage for the timer state machine.
 *
 * This is the regression net for the whole refactor, so it deliberately pins down the
 * previously-broken cases: loop-counter overflow, zero-length breaks, single-loop sessions,
 * and manual-confirmation mode.
 */
class TimerEngineTest {

    private fun config(
        workMinutes: Int = 25,
        workSeconds: Int = 0,
        breakMinutes: Int = 5,
        breakSeconds: Int = 0,
        loops: Int = 4,
        alarm: AlarmSetting = AlarmSetting.Off,
        autoStart: Boolean = true
    ) = TimerConfig(
        workMinutes, workSeconds, breakMinutes, breakSeconds, loops, alarm, autoStart
    )

    // ---------------------------------------------------------------- initial state

    @Test
    fun `initial state starts on first work phase with full duration`() {
        val cfg = config(workMinutes = 25, loops = 4)
        val state = TimerEngine.reset(cfg)

        assertEquals(Phase.WORK, state.phase)
        assertEquals(1, state.currentLoop)
        assertEquals(4, state.totalLoops)
        assertEquals(25 * 60, state.remainingSeconds)
        assertEquals(RunState.IDLE, state.runState)
    }

    // ---------------------------------------------------------------- normal progression

    @Test
    fun `work phase is followed by break in the same loop`() {
        val cfg = config(workMinutes = 1, breakMinutes = 2, loops = 3)
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.advance(running, cfg)

        assertEquals(Phase.BREAK, transition.state.phase)
        assertEquals(1, transition.state.currentLoop, "break belongs to the loop that just worked")
        assertEquals(2 * 60, transition.state.remainingSeconds)
    }

    @Test
    fun `break is followed by the next loop's work phase`() {
        val cfg = config(workMinutes = 1, breakMinutes = 2, loops = 3)
        val onBreak = TimerState(Phase.BREAK, 1, 120, RunState.RUNNING, totalLoops = 3)

        val transition = TimerEngine.advance(onBreak, cfg)

        assertEquals(Phase.WORK, transition.state.phase)
        assertEquals(2, transition.state.currentLoop)
        assertEquals(1 * 60, transition.state.remainingSeconds)
    }

    @Test
    fun `full session walks work-break pairs and completes after the last work phase`() {
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 3, autoStart = true)
        var state = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val visited = mutableListOf<Pair<Phase, Int>>()
        visited += state.phase to state.currentLoop

        repeat(10) {
            if (state.runState == RunState.COMPLETED) return@repeat
            val t = TimerEngine.advance(state, cfg)
            state = t.state
            if (state.runState != RunState.COMPLETED) visited += state.phase to state.currentLoop
        }

        assertEquals(
            listOf(
                Phase.WORK to 1, Phase.BREAK to 1,
                Phase.WORK to 2, Phase.BREAK to 2,
                Phase.WORK to 3
            ),
            visited,
            "session should be W1 B1 W2 B2 W3 with no trailing break"
        )
        assertEquals(RunState.COMPLETED, state.runState)
    }

    // ---------------------------------------------------------------- the loop-overflow bug

    @Test
    fun `currentLoop never exceeds totalLoops across an exhaustive walk`() {
        for (loops in 1..5) {
            for (breakMin in 0..1) {
                for (autoStart in listOf(true, false)) {
                    val cfg = config(
                        workMinutes = 1,
                        breakMinutes = breakMin,
                        loops = loops,
                        autoStart = autoStart
                    )
                    var state = TimerEngine.reset(cfg)
                    repeat(40) {
                        state = TimerEngine.advance(state, cfg).state
                        assertTrue(
                            state.currentLoop in 1..state.totalLoops,
                            "currentLoop=${state.currentLoop} escaped 1..${state.totalLoops} " +
                                "(loops=$loops break=$breakMin autoStart=$autoStart)"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `skipping a break on the final loop completes instead of overflowing the counter`() {
        // Regression: the old skipPhase incremented currentLoop unconditionally,
        // which produced a "round 5 / 4" display.
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 2)
        val lastBreak = TimerState(Phase.BREAK, 2, 60, RunState.RUNNING, totalLoops = 2)

        val transition = TimerEngine.skip(lastBreak, cfg)

        assertEquals(RunState.COMPLETED, transition.state.runState)
        assertEquals(2, transition.state.currentLoop)
        assertTrue(transition.effects.notification!!.allLoopsComplete)
    }

    // ---------------------------------------------------------------- zero-length break

    @Test
    fun `zero-length break is skipped entirely`() {
        val cfg = config(workMinutes = 1, breakMinutes = 0, breakSeconds = 0, loops = 3)
        val working = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.advance(working, cfg)

        assertEquals(Phase.WORK, transition.state.phase, "should never enter a 0-second break")
        assertEquals(2, transition.state.currentLoop)
        assertEquals(60, transition.state.remainingSeconds)
    }

    @Test
    fun `zero-length break never yields a zero-duration running phase`() {
        val cfg = config(workMinutes = 1, breakMinutes = 0, loops = 4)
        var state = TimerEngine.reset(cfg)
        repeat(12) {
            state = TimerEngine.advance(state, cfg).state
            if (state.runState == RunState.RUNNING) {
                assertTrue(
                    state.remainingSeconds > 0,
                    "a RUNNING state must have time on the clock"
                )
            }
        }
    }

    // ---------------------------------------------------------------- single loop

    @Test
    fun `single loop session completes right after its only work phase`() {
        val cfg = config(workMinutes = 1, breakMinutes = 5, loops = 1)
        val working = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.advance(working, cfg)

        assertEquals(RunState.COMPLETED, transition.state.runState)
        assertEquals(1, transition.state.currentLoop)
        assertTrue(
            transition.effects.notification!!.allLoopsComplete,
            "a 1-loop session must not play a trailing break"
        )
    }

    // ---------------------------------------------------------------- autoStart

    @Test
    fun `autoStart on keeps running through a phase boundary`() {
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 2, autoStart = true)
        val working = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.advance(working, cfg)

        assertEquals(RunState.RUNNING, transition.state.runState)
        assertEquals(false, transition.effects.notification!!.awaitingConfirmation)
    }

    @Test
    fun `autoStart off waits for confirmation at a phase boundary`() {
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 2, autoStart = false)
        val working = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.advance(working, cfg)

        assertEquals(RunState.AWAITING_CONFIRMATION, transition.state.runState)
        assertTrue(transition.state.isAwaitingConfirmation)
        assertTrue(transition.effects.notification!!.awaitingConfirmation)
        assertEquals(60, transition.state.remainingSeconds, "next phase is queued, not started")
    }

    @Test
    fun `starting from awaiting confirmation resumes the queued phase`() {
        val cfg = config(workMinutes = 1, breakMinutes = 2, loops = 2, autoStart = false)
        val queued = TimerEngine.advance(
            TimerEngine.start(TimerEngine.reset(cfg), cfg), cfg
        ).state

        val started = TimerEngine.start(queued, cfg)

        assertEquals(RunState.RUNNING, started.runState)
        assertEquals(Phase.BREAK, started.phase)
        assertEquals(120, started.remainingSeconds, "queued time is preserved, not restarted")
    }

    // ---------------------------------------------------------------- tick

    @Test
    fun `tick decrements without crossing a phase boundary`() {
        val cfg = config(workMinutes = 1, loops = 2)
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.tick(running, cfg, elapsedSeconds = 20)

        assertEquals(40, transition.state.remainingSeconds)
        assertEquals(Phase.WORK, transition.state.phase)
        assertNull(transition.effects.notification, "mid-phase tick must not notify")
    }

    @Test
    fun `tick that exhausts the clock advances the phase and emits effects`() {
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 2, alarm = AlarmSetting.Forever)
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.tick(running, cfg, elapsedSeconds = 60)

        assertEquals(Phase.BREAK, transition.state.phase)
        assertNotNull(transition.effects.notification)
        assertEquals(AlarmSetting.Forever, transition.effects.alarm)
    }

    @Test
    fun `tick overshoot still advances exactly one phase`() {
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 3)
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val transition = TimerEngine.tick(running, cfg, elapsedSeconds = 5000)

        assertEquals(Phase.BREAK, transition.state.phase)
        assertEquals(1, transition.state.currentLoop)
        assertTrue(transition.state.remainingSeconds >= 0, "must never go negative")
    }

    @Test
    fun `tick is a no-op when not running`() {
        val cfg = config(workMinutes = 1)
        val idle = TimerEngine.reset(cfg)

        assertEquals(idle, TimerEngine.tick(idle, cfg, 30).state)
        assertEquals(idle, TimerEngine.tick(idle, cfg, 0).state)
    }

    // ---------------------------------------------------------------- pause / reset

    @Test
    fun `pause preserves remaining time and reset restores the configured duration`() {
        val cfg = config(workMinutes = 2, loops = 3)
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)
        val ticked = TimerEngine.tick(running, cfg, 45).state

        val paused = TimerEngine.pause(ticked)
        assertEquals(RunState.PAUSED, paused.runState)
        assertEquals(75, paused.remainingSeconds)

        val reset = TimerEngine.reset(cfg)
        assertEquals(120, reset.remainingSeconds)
        assertEquals(1, reset.currentLoop)
        assertEquals(RunState.IDLE, reset.runState)
    }

    // ---------------------------------------------------------------- alarm effects

    @Test
    fun `alarm effect is absent when the alarm is off`() {
        val cfg = config(workMinutes = 1, loops = 2, alarm = AlarmSetting.Off)
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        assertNull(TimerEngine.advance(running, cfg).effects.alarm)
    }

    @Test
    fun `alarm effect carries the configured setting`() {
        val cfg = config(workMinutes = 1, loops = 2, alarm = AlarmSetting.Fixed(45))
        val running = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        assertEquals(AlarmSetting.Fixed(45), TimerEngine.advance(running, cfg).effects.alarm)
    }

    @Test
    fun `notification reports the phase that ended`() {
        val cfg = config(workMinutes = 1, breakMinutes = 1, loops = 2)
        val working = TimerEngine.start(TimerEngine.reset(cfg), cfg)

        val afterWork = TimerEngine.advance(working, cfg)
        assertEquals(Phase.WORK, afterWork.effects.notification!!.endedPhase)

        val afterBreak = TimerEngine.advance(afterWork.state, cfg)
        assertEquals(Phase.BREAK, afterBreak.effects.notification!!.endedPhase)
    }
}
