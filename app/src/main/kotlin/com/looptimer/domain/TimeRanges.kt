package com.looptimer.domain

/**
 * Single source of truth for user-input bounds.
 *
 * Previously these limits were duplicated and inconsistent: the home screen accepted
 * 0..999 minutes while the preset editor accepted only 0..59, so a preset could not
 * represent a configuration the home screen allowed. Both screens now share these ranges.
 */
object TimeRanges {

    const val SECONDS_PER_MINUTE = 60

    private const val MAX_MINUTES = 999
    private const val MAX_SECONDS = SECONDS_PER_MINUTE - 1
    private const val MAX_LOOPS = 99
    private const val MAX_ALARM_SECONDS = 3600

    val MINUTES = 0..MAX_MINUTES
    val SECONDS = 0..MAX_SECONDS
    val LOOPS = 1..MAX_LOOPS

    /** Bounds for a fixed-length alarm, in seconds. */
    val ALARM_SECONDS = 1..MAX_ALARM_SECONDS

    fun coerceMinutes(value: Int): Int = value.coerceIn(MINUTES)
    fun coerceSeconds(value: Int): Int = value.coerceIn(SECONDS)
    fun coerceLoops(value: Int): Int = value.coerceIn(LOOPS)
    fun coerceAlarmSeconds(value: Int): Int = value.coerceIn(ALARM_SECONDS)
}
