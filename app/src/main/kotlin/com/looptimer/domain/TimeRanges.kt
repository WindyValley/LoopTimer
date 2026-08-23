package com.looptimer.domain

/**
 * Single source of truth for user-input bounds.
 *
 * Previously these limits were duplicated and inconsistent: the home screen accepted
 * 0..999 minutes while the preset editor accepted only 0..59, so a preset could not
 * represent a configuration the home screen allowed. Both screens now share these ranges.
 */
object TimeRanges {
    val MINUTES = 0..999
    val SECONDS = 0..59
    val LOOPS = 1..99

    /** Bounds for a fixed-length alarm, in seconds. */
    val ALARM_SECONDS = 1..3600

    fun coerceMinutes(value: Int): Int = value.coerceIn(MINUTES)
    fun coerceSeconds(value: Int): Int = value.coerceIn(SECONDS)
    fun coerceLoops(value: Int): Int = value.coerceIn(LOOPS)
    fun coerceAlarmSeconds(value: Int): Int = value.coerceIn(ALARM_SECONDS)
}
