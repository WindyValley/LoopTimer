package com.looptimer.domain

/**
 * How the end-of-phase alarm should behave.
 *
 * This replaces an [Int] field where `0` meant "off" and `-1` was a magic value meaning
 * "ring forever". That encoding was error-prone and, worse, unrepresentable in the preset
 * editor (which clamped input to 1..300), so presets silently could not express "ring forever".
 * Modelling the three cases explicitly makes every state reachable and impossible to confuse.
 */
sealed interface AlarmSetting {

    /** No sound or vibration when a phase ends. */
    data object Off : AlarmSetting

    /** Ring for [seconds], then stop automatically. */
    data class Fixed(val seconds: Int) : AlarmSetting {
        init {
            require(seconds in TimeRanges.ALARM_SECONDS) {
                "Fixed alarm duration must be in ${TimeRanges.ALARM_SECONDS}, was $seconds"
            }
        }
    }

    /** Ring until the user explicitly stops it. */
    data object Forever : AlarmSetting

    val isEnabled: Boolean get() = this != Off

    companion object {
        val DEFAULT_FIXED = Fixed(30)

        /**
         * Decodes the legacy persisted representation.
         *
         * Legacy encoding: `useAlarm=false` -> off, `alarmDuration <= -1` -> forever,
         * `alarmDuration == 0` -> off, otherwise a fixed duration.
         */
        fun fromLegacy(useAlarm: Boolean, alarmDuration: Int): AlarmSetting = when {
            !useAlarm -> Off
            alarmDuration < 0 -> Forever
            alarmDuration == 0 -> Off
            else -> Fixed(TimeRanges.coerceAlarmSeconds(alarmDuration))
        }
    }

    /** Legacy `useAlarm` value, for backwards-compatible persistence. */
    fun toLegacyUseAlarm(): Boolean = isEnabled

    /** Legacy `alarmDuration` value, for backwards-compatible persistence. */
    fun toLegacyDuration(): Int = when (this) {
        is Off -> 0
        is Fixed -> seconds
        is Forever -> -1
    }
}
