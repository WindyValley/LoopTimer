package com.looptimer.domain

/**
 * An immutable timer configuration: what the user set up, never where the countdown currently is.
 *
 * Keeping configuration separate from [TimerState] is what fixes the old bug where editing the
 * work duration did not update the displayed time - the remaining time is now derived from this
 * config rather than being an independent, silently stale variable.
 *
 * All values are normalised on construction, so an out-of-range value cannot reach the engine.
 */
data class TimerConfig(
    val workMinutes: Int = 25,
    val workSeconds: Int = 0,
    val breakMinutes: Int = 5,
    val breakSeconds: Int = 0,
    val loops: Int = 4,
    val alarm: AlarmSetting = AlarmSetting.Off,
    val autoStart: Boolean = true
) {
    init {
        require(workMinutes in TimeRanges.MINUTES) { "workMinutes out of range: $workMinutes" }
        require(workSeconds in TimeRanges.SECONDS) { "workSeconds out of range: $workSeconds" }
        require(breakMinutes in TimeRanges.MINUTES) { "breakMinutes out of range: $breakMinutes" }
        require(breakSeconds in TimeRanges.SECONDS) { "breakSeconds out of range: $breakSeconds" }
        require(loops in TimeRanges.LOOPS) { "loops out of range: $loops" }
    }

    /** Total configured work duration in seconds. */
    val workTotalSeconds: Int get() = workMinutes * 60 + workSeconds

    /** Total configured break duration in seconds. Zero means "skip breaks entirely". */
    val breakTotalSeconds: Int get() = breakMinutes * 60 + breakSeconds

    val hasWork: Boolean get() = workTotalSeconds > 0
    val hasBreak: Boolean get() = breakTotalSeconds > 0

    fun durationOf(phase: Phase): Int = when (phase) {
        Phase.WORK -> workTotalSeconds
        Phase.BREAK -> breakTotalSeconds
    }

    companion object {
        /**
         * Builds a config from possibly-invalid input by clamping rather than throwing.
         * Use this at the persistence and UI boundaries; use the constructor internally.
         */
        fun coerced(
            workMinutes: Int,
            workSeconds: Int,
            breakMinutes: Int,
            breakSeconds: Int,
            loops: Int,
            alarm: AlarmSetting,
            autoStart: Boolean
        ): TimerConfig = TimerConfig(
            workMinutes = TimeRanges.coerceMinutes(workMinutes),
            workSeconds = TimeRanges.coerceSeconds(workSeconds),
            breakMinutes = TimeRanges.coerceMinutes(breakMinutes),
            breakSeconds = TimeRanges.coerceSeconds(breakSeconds),
            loops = TimeRanges.coerceLoops(loops),
            alarm = alarm,
            autoStart = autoStart
        )
    }
}
