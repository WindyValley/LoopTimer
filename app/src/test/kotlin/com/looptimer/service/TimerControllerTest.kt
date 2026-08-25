package com.looptimer.service

import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * Verifies the timing layer: remaining time is derived from a monotonic deadline rather than
 * accumulated per-second decrements, phase boundaries are delegated to a scheduler, and state
 * ownership is independent of any UI.
 *
 * [ElapsedClock] is backed by the coroutine test scheduler's virtual clock, so advancing virtual
 * time advances the timer's notion of "now" in lockstep. The [FakeScheduler] stands in for
 * `AlarmManager`: the test fires deadlines explicitly, exactly as the OS would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerControllerTest {

    /** Records the requested deadline so the test can assert on it and fire it on demand. */
    private class FakeScheduler : DeadlineScheduler {
        var scheduledAt: Long? = null
            private set
        var scheduleCount = 0
            private set
        var cancelCount = 0
            private set

        override fun schedule(triggerAtElapsedMs: Long) {
            scheduledAt = triggerAtElapsedMs
            scheduleCount++
        }

        override fun cancel() {
            scheduledAt = null
            cancelCount++
        }
    }

    private fun config(
        workMinutes: Int = 0,
        workSeconds: Int = 0,
        breakMinutes: Int = 0,
        breakSeconds: Int = 0,
        loops: Int = 2,
        alarm: AlarmSetting = AlarmSetting.Off,
        autoStart: Boolean = true
    ) = TimerConfig(workMinutes, workSeconds, breakMinutes, breakSeconds, loops, alarm, autoStart)

    private class Fixture(
        val controller: TimerController,
        val scheduler: FakeScheduler,
        val events: MutableList<TimerEvent>
    )

    private fun timerTest(
        config: TimerConfig,
        body: suspend TestScope.(Fixture) -> Unit
    ) = runTest {
        val scheduler = FakeScheduler()
        val controller = TimerController(
            scope = this,
            clock = ElapsedClock { testScheduler.currentTime },
            scheduler = scheduler,
            initialConfig = config
        )
        val events = mutableListOf<TimerEvent>()
        val collector: Job = launch { controller.events.collect { events += it } }

        try {
            body(Fixture(controller, scheduler, events))
        } finally {
            controller.dispose()
            collector.cancel()
        }
    }

    /** Simulates the OS delivering the scheduled wake-up. */
    private fun TestScope.fireDeadline(f: Fixture) {
        val at = requireNotNull(f.scheduler.scheduledAt) { "no deadline was scheduled" }
        val delta = at - testScheduler.currentTime
        if (delta > 0) advanceTimeBy(delta)
        runCurrent()
        f.controller.onDeadlineReached()
        runCurrent()
    }

    // ---------------------------------------------------------------- deadline scheduling

    @Test
    fun `starting schedules a deadline at the end of the phase`() =
        timerTest(config(workMinutes = 1)) { f ->
            f.controller.start()

            assertEquals(60_000L, f.scheduler.scheduledAt, "deadline should be 60s from t=0")
            assertEquals(60_000L, f.controller.deadlineElapsedMs.value)
        }

    @Test
    fun `no per-second work happens between boundaries`() =
        timerTest(config(workMinutes = 5)) { f ->
            f.controller.start()
            val schedulesAfterStart = f.scheduler.scheduleCount

            // Four minutes of virtual time with nothing driving the controller.
            advanceTimeBy(240_000)
            runCurrent()

            assertEquals(
                schedulesAfterStart,
                f.scheduler.scheduleCount,
                "the controller must not reschedule while simply waiting"
            )
        }

    @Test
    fun `remaining time is derived from the clock and does not drift`() =
        timerTest(config(workMinutes = 1)) { f ->
            f.controller.start()

            advanceTimeBy(10_000)
            f.controller.refresh()

            assertEquals(
                50,
                f.controller.state.value.remainingSeconds,
                "remaining time must track the clock, not the number of iterations"
            )
        }

    @Test
    fun `long run accumulates no error`() =
        timerTest(config(workMinutes = 5)) { f ->
            f.controller.start()

            repeat(100) {
                advanceTimeBy(1_500)
                f.controller.refresh()
            }

            assertEquals(150, f.controller.state.value.remainingSeconds)
        }

    // ---------------------------------------------------------------- progression

    @Test
    fun `firing the deadline advances to the break and reschedules`() =
        timerTest(config(workSeconds = 30, breakSeconds = 20)) { f ->
            f.controller.start()
            fireDeadline(f)

            assertEquals(Phase.BREAK, f.controller.state.value.phase)
            assertTrue(f.controller.state.value.isRunning)
            assertEquals(
                50_000L,
                f.scheduler.scheduledAt,
                "break deadline should be 20s after the 30s work phase"
            )
        }

    @Test
    fun `a full session walks every phase and then stops scheduling`() =
        timerTest(config(workSeconds = 5, breakSeconds = 5, loops = 3)) { f ->
            f.controller.start()

            val s0 = f.controller.state.value
            val visited = mutableListOf(s0.phase to s0.currentLoop)
            repeat(6) {
                if (f.controller.state.value.isRunning) {
                    fireDeadline(f)
                    if (f.controller.state.value.isRunning) {
                        visited += f.controller.state.value.phase to f.controller.state.value.currentLoop
                    }
                }
            }

            assertEquals(
                listOf(
                    Phase.WORK to 1, Phase.BREAK to 1,
                    Phase.WORK to 2, Phase.BREAK to 2,
                    Phase.WORK to 3
                ),
                visited
            )
            assertEquals(RunState.COMPLETED, f.controller.state.value.runState)
            assertNull(f.controller.deadlineElapsedMs.value, "a finished session holds no deadline")
        }

    @Test
    fun `waking early re-arms instead of advancing`() =
        timerTest(config(workSeconds = 30, breakSeconds = 10)) { f ->
            f.controller.start()

            // A spurious wake-up 10s in: 20s still remain.
            advanceTimeBy(10_000)
            runCurrent()
            f.controller.onDeadlineReached()
            runCurrent()

            assertEquals(Phase.WORK, f.controller.state.value.phase, "must not skip the phase")
            assertEquals(20, f.controller.state.value.remainingSeconds)
            assertEquals(30_000L, f.scheduler.scheduledAt, "deadline unchanged")
        }

    @Test
    fun `zero length break is never scheduled as a phase`() =
        timerTest(config(workSeconds = 4, breakSeconds = 0, loops = 3)) { f ->
            f.controller.start()

            repeat(6) {
                if (f.controller.state.value.isRunning) {
                    fireDeadline(f)
                    val s = f.controller.state.value
                    if (s.isRunning) {
                        assertTrue(s.remainingSeconds > 0, "a running phase must have time left")
                        assertEquals(Phase.WORK, s.phase, "0-second break must be skipped")
                    }
                }
            }
            assertEquals(RunState.COMPLETED, f.controller.state.value.runState)
        }

    // ---------------------------------------------------------------- pause / resume / reset

    @Test
    fun `pause freezes the remaining time and cancels the deadline`() =
        timerTest(config(workMinutes = 1)) { f ->
            f.controller.start()
            advanceTimeBy(15_000)
            f.controller.pause()

            assertEquals(45, f.controller.state.value.remainingSeconds)
            assertNull(f.controller.deadlineElapsedMs.value)
            assertTrue(f.scheduler.cancelCount > 0, "the pending wake-up must be cancelled")

            // Time passing while paused must not be consumed.
            advanceTimeBy(60_000)
            f.controller.refresh()
            assertEquals(45, f.controller.state.value.remainingSeconds)
            assertEquals(RunState.PAUSED, f.controller.state.value.runState)
        }

    @Test
    fun `resuming continues from the frozen value with a fresh deadline`() =
        timerTest(config(workMinutes = 1)) { f ->
            f.controller.start()
            advanceTimeBy(20_000)
            f.controller.pause()

            advanceTimeBy(120_000) // long idle gap
            f.controller.start()

            assertEquals(
                testScheduler.currentTime + 40_000L,
                f.scheduler.scheduledAt,
                "the new deadline is 40s from the moment of resuming"
            )

            advanceTimeBy(10_000)
            f.controller.refresh()
            assertEquals(30, f.controller.state.value.remainingSeconds)
        }

    @Test
    fun `reset returns to the first work phase, cancels the deadline and silences the alarm`() =
        timerTest(config(workSeconds = 30, breakSeconds = 10, loops = 3)) { f ->
            f.controller.start()
            advanceTimeBy(10_000)
            f.controller.reset()
            runCurrent()

            val s = f.controller.state.value
            assertEquals(Phase.WORK, s.phase)
            assertEquals(1, s.currentLoop)
            assertEquals(30, s.remainingSeconds)
            assertEquals(RunState.IDLE, s.runState)
            assertNull(f.controller.deadlineElapsedMs.value)
            assertTrue(f.events.any { it is TimerEvent.StopAlarm })
        }

    @Test
    fun `skip advances immediately without waiting for the clock`() =
        timerTest(config(workMinutes = 25, breakMinutes = 5, loops = 3)) { f ->
            f.controller.start()
            f.controller.skip()
            runCurrent()

            assertEquals(Phase.BREAK, f.controller.state.value.phase)
            assertEquals(5 * 60, f.controller.state.value.remainingSeconds)
        }

    // ---------------------------------------------------------------- autoStart

    @Test
    fun `autoStart off holds at the boundary and schedules nothing`() =
        timerTest(config(workSeconds = 5, breakSeconds = 30, autoStart = false)) { f ->
            f.controller.start()
            fireDeadline(f)

            assertEquals(RunState.AWAITING_CONFIRMATION, f.controller.state.value.runState)
            assertEquals(Phase.BREAK, f.controller.state.value.phase)
            assertNull(
                f.controller.deadlineElapsedMs.value,
                "a queued phase must not be counting down"
            )

            // Time passing must not consume the queued phase.
            advanceTimeBy(60_000)
            f.controller.refresh()
            assertEquals(30, f.controller.state.value.remainingSeconds)

            f.controller.start()
            advanceTimeBy(10_000)
            f.controller.refresh()
            assertEquals(20, f.controller.state.value.remainingSeconds)
        }

    // ---------------------------------------------------------------- alarm

    @Test
    fun `crossing a boundary raises the alarm and reports the ended phase`() =
        timerTest(
            config(workSeconds = 5, breakSeconds = 5, alarm = AlarmSetting.Forever)
        ) { f ->
            f.controller.start()
            fireDeadline(f)

            assertTrue(
                f.events.any { it is TimerEvent.StartAlarm && it.setting == AlarmSetting.Forever },
                "expected StartAlarm, saw ${f.events}"
            )
            assertTrue(f.controller.alarmRinging.value)
            val ended = f.events.filterIsInstance<TimerEvent.PhaseEnded>().single()
            assertEquals(Phase.WORK, ended.info.endedPhase)
        }

    @Test
    fun `a fixed alarm stops itself after its duration`() =
        timerTest(config(workSeconds = 5, breakMinutes = 1, alarm = AlarmSetting.Fixed(10))) { f ->
            f.controller.start()
            fireDeadline(f)
            assertTrue(f.controller.alarmRinging.value)

            advanceTimeBy(10_001)
            runCurrent()

            assertTrue(!f.controller.alarmRinging.value, "fixed alarm should have auto-stopped")
        }

    @Test
    fun `a forever alarm keeps ringing until stopped`() =
        timerTest(config(workSeconds = 5, breakMinutes = 10, alarm = AlarmSetting.Forever)) { f ->
            f.controller.start()
            fireDeadline(f)

            advanceTimeBy(300_000)
            runCurrent()
            assertTrue(f.controller.alarmRinging.value, "forever alarm must not self-stop")

            f.controller.stopAlarm()
            runCurrent()
            assertTrue(!f.controller.alarmRinging.value)
        }

    @Test
    fun `no alarm effect is emitted when the alarm is off`() =
        timerTest(config(workSeconds = 5, breakSeconds = 5, alarm = AlarmSetting.Off)) { f ->
            f.controller.start()
            fireDeadline(f)

            assertTrue(f.events.none { it is TimerEvent.StartAlarm })
            assertTrue(!f.controller.alarmRinging.value)
        }

    // ---------------------------------------------------------------- config changes

    @Test
    fun `editing config while idle re-derives the displayed duration`() =
        timerTest(config(workMinutes = 25, loops = 4)) { f ->
            assertEquals(25 * 60, f.controller.state.value.remainingSeconds)

            f.controller.updateConfig(config(workMinutes = 50, loops = 4))

            assertEquals(
                50 * 60,
                f.controller.state.value.remainingSeconds,
                "regression: changing the work duration used to leave the display stale"
            )
        }

    @Test
    fun `shrinking the loop count clamps the current loop`() =
        timerTest(config(workSeconds = 5, breakSeconds = 5, loops = 5)) { f ->
            f.controller.start()
            repeat(4) { if (f.controller.state.value.isRunning) fireDeadline(f) }
            assertTrue(f.controller.state.value.currentLoop >= 3)

            f.controller.updateConfig(config(workSeconds = 5, breakSeconds = 5, loops = 2))

            val s = f.controller.state.value
            assertTrue(
                s.currentLoop <= s.totalLoops,
                "currentLoop=${s.currentLoop} must not exceed totalLoops=${s.totalLoops}"
            )
        }
}
