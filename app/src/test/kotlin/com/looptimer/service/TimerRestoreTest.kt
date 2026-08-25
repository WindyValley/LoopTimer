package com.looptimer.service

import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerState
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers recovery of a run interrupted by process death.
 *
 * Regression: the service used to rebuild itself from the configuration alone
 * (`TimerEngine.reset(config)`), so reopening the app after the process was killed showed the full
 * duration again while the pending `AlarmManager` wake-up still pointed at the original boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerRestoreTest {

    private class FakeScheduler : DeadlineScheduler {
        var scheduledAt: Long? = null
            private set
        var cancelCount = 0
            private set

        override fun schedule(triggerAtElapsedMs: Long) { scheduledAt = triggerAtElapsedMs }
        override fun cancel() { scheduledAt = null; cancelCount++ }
    }

    private fun config(workMinutes: Int = 25, breakMinutes: Int = 5, loops: Int = 4) =
        TimerConfig(
            workMinutes = workMinutes,
            breakMinutes = breakMinutes,
            loops = loops,
            autoStart = true
        )

    private fun restoreTest(
        config: TimerConfig,
        body: suspend TestScope.(TimerController, FakeScheduler) -> Unit
    ) = runTest {
        val scheduler = FakeScheduler()
        val controller = TimerController(
            scope = this,
            clock = ElapsedClock { testScheduler.currentTime },
            scheduler = scheduler,
            initialConfig = config
        )
        try {
            body(controller, scheduler)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `restoring a mid-phase run recomputes the remaining time from the deadline`() =
        restoreTest(config(workMinutes = 25)) { controller, scheduler ->
            // Simulate: the run started at t=0 with a 25-minute deadline, the process died,
            // and 10 minutes of real time passed before the service was recreated.
            advanceTimeBy(600_000)

            val persisted = TimerState(
                phase = Phase.WORK,
                currentLoop = 1,
                remainingSeconds = 25 * 60, // stale value as written at start
                runState = RunState.RUNNING,
                totalLoops = 4
            )
            controller.restore(persisted, deadlineElapsedMs = 25 * 60 * 1000L)

            assertEquals(
                15 * 60,
                controller.state.value.remainingSeconds,
                "must trust the deadline, not the stale stored seconds"
            )
            assertTrue(controller.state.value.isRunning)
            assertEquals(
                25 * 60 * 1000L,
                scheduler.scheduledAt,
                "the pending wake-up must be re-armed at the original boundary"
            )
        }

    @Test
    fun `restoring preserves phase and loop position`() =
        restoreTest(config()) { controller, _ ->
            advanceTimeBy(60_000)
            val persisted = TimerState(
                phase = Phase.BREAK,
                currentLoop = 3,
                remainingSeconds = 300,
                runState = RunState.RUNNING,
                totalLoops = 4
            )
            controller.restore(persisted, deadlineElapsedMs = 300_000L)

            val s = controller.state.value
            assertEquals(Phase.BREAK, s.phase, "regression: used to reset to WORK")
            assertEquals(3, s.currentLoop, "regression: used to reset to loop 1")
            assertEquals(4, s.totalLoops)
        }

    @Test
    fun `a deadline that elapsed while dead advances instead of showing stale time`() =
        restoreTest(config(workMinutes = 1, breakMinutes = 2)) { controller, scheduler ->
            // The work phase should have ended at t=60s, but we were dead until t=90s.
            advanceTimeBy(90_000)

            val persisted = TimerState(
                phase = Phase.WORK,
                currentLoop = 1,
                remainingSeconds = 60,
                runState = RunState.RUNNING,
                totalLoops = 4
            )
            controller.restore(persisted, deadlineElapsedMs = 60_000L)
            runCurrent()

            assertEquals(
                Phase.BREAK,
                controller.state.value.phase,
                "the missed boundary must be applied on restore"
            )
            assertNotNull(scheduler.scheduledAt, "the next phase must be scheduled")
        }

    @Test
    fun `restoring a paused run keeps the frozen time and schedules nothing`() =
        restoreTest(config()) { controller, scheduler ->
            advanceTimeBy(120_000)
            val persisted = TimerState(
                phase = Phase.WORK,
                currentLoop = 2,
                remainingSeconds = 742,
                runState = RunState.PAUSED,
                totalLoops = 4
            )
            controller.restore(persisted, deadlineElapsedMs = null)

            assertEquals(742, controller.state.value.remainingSeconds, "paused time is frozen")
            assertEquals(RunState.PAUSED, controller.state.value.runState)
            assertNull(scheduler.scheduledAt, "a paused run must not hold a wake-up")
        }

    @Test
    fun `restoring an awaiting-confirmation run does not start counting`() =
        restoreTest(config()) { controller, scheduler ->
            val persisted = TimerState(
                phase = Phase.BREAK,
                currentLoop = 1,
                remainingSeconds = 300,
                runState = RunState.AWAITING_CONFIRMATION,
                totalLoops = 4
            )
            controller.restore(persisted, deadlineElapsedMs = null)

            assertEquals(RunState.AWAITING_CONFIRMATION, controller.state.value.runState)
            assertEquals(300, controller.state.value.remainingSeconds)
            assertNull(scheduler.scheduledAt)
        }

    @Test
    fun `restoring then resuming continues from the restored position`() =
        restoreTest(config(workMinutes = 25, breakMinutes = 5)) { controller, _ ->
            advanceTimeBy(600_000)
            controller.restore(
                TimerState(Phase.WORK, 2, 25 * 60, RunState.PAUSED, totalLoops = 4),
                deadlineElapsedMs = null
            )

            controller.start()

            assertEquals(2, controller.state.value.currentLoop)
            assertEquals(Phase.WORK, controller.state.value.phase)
            assertTrue(controller.state.value.isRunning)
        }

    @Test
    fun `a restored run continues to the following phases normally`() =
        restoreTest(config(workMinutes = 1, breakMinutes = 1, loops = 2)) { controller, scheduler ->
            advanceTimeBy(30_000)
            controller.restore(
                TimerState(Phase.WORK, 1, 60, RunState.RUNNING, totalLoops = 2),
                deadlineElapsedMs = 60_000L
            )
            assertEquals(30, controller.state.value.remainingSeconds)

            // Reach the restored boundary.
            advanceTimeBy(30_000)
            controller.onDeadlineReached()
            runCurrent()

            assertEquals(Phase.BREAK, controller.state.value.phase)
            assertNotNull(scheduler.scheduledAt)
        }

    @Test
    fun `restoring an idle snapshot cancels any stale wake-up`() =
        restoreTest(config()) { controller, scheduler ->
            controller.restore(
                TimerState(Phase.WORK, 1, 1500, RunState.IDLE, totalLoops = 4),
                deadlineElapsedMs = null
            )

            assertEquals(RunState.IDLE, controller.state.value.runState)
            assertTrue(scheduler.cancelCount > 0, "a stale alarm must not outlive an idle state")
        }

    @Test
    fun `the alarm still fires for a restored run`() =
        restoreTest(
            TimerConfig(workMinutes = 1, breakMinutes = 1, loops = 2, alarm = AlarmSetting.Forever)
        ) { controller, _ ->
            advanceTimeBy(59_000)
            controller.restore(
                TimerState(Phase.WORK, 1, 60, RunState.RUNNING, totalLoops = 2),
                deadlineElapsedMs = 60_000L
            )
            advanceTimeBy(1_000)
            controller.onDeadlineReached()
            runCurrent()

            assertTrue(controller.alarmRinging.value, "a restored run must still alert the user")
        }
}
