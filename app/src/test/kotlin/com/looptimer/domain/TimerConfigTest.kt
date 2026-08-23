package com.looptimer.domain

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * Covers config normalisation and the derived durations that the engine reads, notably the
 * unified input bounds that replaced the home screen's 0..999 vs preset editor's 0..59 mismatch.
 */
class TimerConfigTest {

    @Test
    fun `derived totals combine minutes and seconds`() {
        val cfg = TimerConfig(
            workMinutes = 2, workSeconds = 30,
            breakMinutes = 1, breakSeconds = 15,
            loops = 2
        )
        assertEquals(150, cfg.workTotalSeconds)
        assertEquals(75, cfg.breakTotalSeconds)
    }

    @Test
    fun `hasBreak reflects a zero-length break`() {
        val noBreak = TimerConfig(breakMinutes = 0, breakSeconds = 0)
        val withBreak = TimerConfig(breakMinutes = 0, breakSeconds = 1)

        assertEquals(false, noBreak.hasBreak)
        assertEquals(true, withBreak.hasBreak)
    }

    @Test
    fun `constructor rejects out of range values`() {
        assertFailsWith<IllegalArgumentException> { TimerConfig(workSeconds = 60) }
        assertFailsWith<IllegalArgumentException> { TimerConfig(workMinutes = -1) }
        assertFailsWith<IllegalArgumentException> { TimerConfig(loops = 0) }
        assertFailsWith<IllegalArgumentException> { TimerConfig(loops = 100) }
    }

    @Test
    fun `coerced clamps instead of throwing at boundaries`() {
        val cfg = TimerConfig.coerced(
            workMinutes = 5000,
            workSeconds = 99,
            breakMinutes = -3,
            breakSeconds = -1,
            loops = 500,
            alarm = AlarmSetting.Off,
            autoStart = true
        )

        assertEquals(999, cfg.workMinutes)
        assertEquals(59, cfg.workSeconds)
        assertEquals(0, cfg.breakMinutes)
        assertEquals(0, cfg.breakSeconds)
        assertEquals(99, cfg.loops)
    }

    @Test
    fun `minute range is shared by both screens and allows more than 59`() {
        // Regression: the preset editor used to cap minutes at 59, so it could not express
        // a configuration the home screen accepted.
        assertEquals(999, TimeRanges.MINUTES.last)
        assertEquals(60, TimeRanges.coerceMinutes(60))
    }

    @Test
    fun `durationOf resolves per phase`() {
        val cfg = TimerConfig(workMinutes = 1, breakMinutes = 2)
        assertEquals(60, cfg.durationOf(Phase.WORK))
        assertEquals(120, cfg.durationOf(Phase.BREAK))
    }
}
