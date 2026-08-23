package com.looptimer.service

import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Verifies the timing layer: that the countdown is derived from a monotonic deadline rather
 * than accumulated per-second decrements, and that state ownership survives independently
 * of any UI.
 *
 * The [ElapsedClock] is backed by the coroutine test scheduler's virtual clock, so advancing
 * virtual time advances the timer's notion of "now" in lockstep - exactly like the real
 * pairing of `elapsedRealtime()` and `delay()`, but deterministic and instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerControllerTest {

    private fun config(
        workMinutes: Int = 0,
        workSeconds: Int = 0,
        breakMinutes: Int = 0,
        breakSeconds: Int = 0,
        loops: Int = 2,
        alarm: AlarmSetting = AlarmSetting.Off,
        autoStart: Boolean = true
    ) = TimerConfig(workMinutes, workSeconds, breakMinutes, breakSeconds, loops, alarm, autoStart)

    /**
     * Runs [body] with a controller whose clock is the scheduler's virtual clock.
     * Use `advanceTimeBy(...)` to move time; the controller observes the same instant.
     */
    private fun timerTest(
        config: TimerConfig,
        body: suspend TestScope.(TimerController, MutableList<TimerEvent>) -> Unit
    ) = runTest {
        val clock = ElapsedClock { testScheduler.currentTime }
        val controller = TimerController(this, clock, config)
        val events = mutableListOf<TimerEvent>()
        val collector: Job = launch { controller.events.collect { events += it } }

        try {
            body(controller, events)
        } finally {
            controller.dispose()
            collector.cancel()
        }
    }

    @Test
    fun `countdown is derived from the deadline and does not drift`() =
        timerTest(config(workMinutes = 1, loops = 2)) { controller, _ ->
            controller.start()

            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(
                50,
                controller.state.value.remainingSeconds,
                "remaining time must track the clock, not the number of tick iterations"
            )
        }

    @Test
    fun `long run does not accumulate error`() =
        timerTest(config(workMinutes = 5, loops = 2)) { controller, _ ->
            controller.start()

            // Many irregular advances; a decrementing counter would lose time here.
            repeat(100) {
                advanceTimeBy(1_500)
                runCurrent()
            }

            // 100 * 1.5s = 150s of a 300s phase.
            assertEquals(150, controller.state.value.remainingSeconds)
        }

    @Test
    fun `phase rolls over when the deadline passes while the process was asleep`() =
        timerTest(config(workSeconds = 30, breakSeconds = 20, loops = 2)) { controller, _ ->
            controller.start()
            assertEquals(Phase.WORK, controller.state.value.phase)

            // Jump straight past the end of the work phase.
            advanceTimeBy(45_000)
            runCurrent()

            assertEquals(Phase.BREAK, controller.state.value.phase)
            assertTrue(controller.state.value.isRunning)
        }

    @Test
    fun `pause freezes the derived remaining time`() =
        timerTest(config(workMinutes = 1, loops = 2)) { controller, _ ->
            controller.start()
            advanceTimeBy(15_000)
            runCurrent()
            controller.pause()

            val frozen = controller.state.value.remainingSeconds
            assertEquals(45, frozen)

            // Time keeps passing while paused; the timer must not consume it.
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(frozen, controller.state.value.remainingSeconds)
            assertEquals(RunState.PAUSED, controller.state.value.runState)
        }

    @Test
    fun `resume after pause continues from the frozen value`() =
        timerTest(config(workMinutes = 1, loops = 2)) { controller, _ ->
            controller.start()
            advanceTimeBy(20_000)
            runCurrent()
            controller.pause()

            advanceTimeBy(120_000) // long idle gap
            controller.start()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(30, controller.state.value.remainingSeconds)
        }

    @Test
    fun `emits alarm and notification effects at a phase boundary`() =
        timerTest(
            config(workSeconds = 5, breakSeconds = 5, loops = 2, alarm = AlarmSetting.Forever)
        ) { controller, events ->
            controller.start()
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(
                events.any { it is TimerEvent.StartAlarm && it.setting == AlarmSetting.Forever },
                "expected StartAlarm, saw $events"
            )
            assertTrue(events.any { it is TimerEvent.PhaseEnded }, "expected PhaseEnded, saw $events")
        }

    @Test
    fun `autoStart off holds at the boundary until start is called`() =
        timerTest(
            config(workSeconds = 5, breakSeconds = 30, loops = 2, autoStart = false)
        ) { controller, _ ->
            controller.start()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(RunState.AWAITING_CONFIRMATION, controller.state.value.runState)
            assertEquals(Phase.BREAK, controller.state.value.phase)

            // Time passing must not consume the queued phase.
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(30, controller.state.value.remainingSeconds)

            controller.start()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(20, controller.state.value.remainingSeconds)
        }

    @Test
    fun `editing config while idle re-derives the displayed duration`() =
        timerTest(config(workMinutes = 25, loops = 4)) { controller, _ ->
            assertEquals(25 * 60, controller.state.value.remainingSeconds)

            controller.updateConfig(config(workMinutes = 50, loops = 4))

            assertEquals(
                50 * 60,
                controller.state.value.remainingSeconds,
                "regression: changing the work duration used to leave the display stale"
            )
        }

    @Test
    fun `shrinking the loop count clamps the current loop`() =
        timerTest(config(workSeconds = 5, breakSeconds = 5, loops = 5)) { controller, _ ->
            controller.start()
            repeat(4) {
                advanceTimeBy(5_000)
                runCurrent()
            }
            val loopBefore = controller.state.value.currentLoop
            assertTrue(loopBefore >= 3, "expected to be past loop 2, was $loopBefore")

            controller.updateConfig(config(workSeconds = 5, breakSeconds = 5, loops = 2))

            val s = controller.state.value
            assertTrue(
                s.currentLoop <= s.totalLoops,
                "currentLoop=${s.currentLoop} must not exceed totalLoops=${s.totalLoops}"
            )
        }

    @Test
    fun `reset returns to the first work phase and stops the alarm`() =
        timerTest(config(workSeconds = 30, breakSeconds = 10, loops = 3)) { controller, events ->
            controller.start()
            advanceTimeBy(10_000)
            runCurrent()
            controller.reset()
            runCurrent()

            val s = controller.state.value
            assertEquals(Phase.WORK, s.phase)
            assertEquals(1, s.currentLoop)
            assertEquals(30, s.remainingSeconds)
            assertEquals(RunState.IDLE, s.runState)
            assertTrue(events.any { it is TimerEvent.StopAlarm }, "reset must silence the alarm")
        }

    @Test
    fun `skip advances immediately without waiting for the clock`() =
        timerTest(config(workMinutes = 25, breakMinutes = 5, loops = 3)) { controller, _ ->
            controller.start()
            controller.skip()
            runCurrent()

            assertEquals(Phase.BREAK, controller.state.value.phase)
            assertEquals(5 * 60, controller.state.value.remainingSeconds)
        }

    @Test
    fun `session completes after the final work phase`() =
        timerTest(config(workSeconds = 5, breakSeconds = 5, loops = 2)) { controller, _ ->
            controller.start()
            repeat(6) {
                advanceTimeBy(5_000)
                runCurrent()
            }

            assertEquals(RunState.COMPLETED, controller.state.value.runState)
        }

    @Test
    fun `ticker stops once the session completes`() =
        timerTest(config(workSeconds = 3, loops = 1)) { controller, _ ->
            controller.start()
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals(RunState.COMPLETED, controller.state.value.runState)

            // Further time must not move anything.
            val snapshot = controller.state.value
            advanceTimeBy(100_000)
            runCurrent()

            assertEquals(snapshot, controller.state.value)
        }

    @Test
    fun `zero length break never produces a stuck zero-duration phase`() =
        timerTest(config(workSeconds = 4, breakSeconds = 0, loops = 3)) { controller, _ ->
            controller.start()

            repeat(10) {
                advanceTimeBy(4_000)
                runCurrent()
                val s = controller.state.value
                if (s.isRunning) {
                    assertTrue(s.remainingSeconds > 0, "a running phase must have time on the clock")
                }
            }

            assertEquals(RunState.COMPLETED, controller.state.value.runState)
        }
}
