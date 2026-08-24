package com.looptimer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.TimerConfig
import org.json.JSONObject

/**
 * Persistence for the active timer configuration and the theme choice.
 *
 * Improvements over the previous `PresetsManager`:
 * - The home screen's configuration is now persisted. It used to be written only when a preset
 *   was selected, so any manual edit was lost when the process died.
 * - Parsing is defensive (`opt*` with defaults, values clamped) and versioned, so a single
 *   malformed record can no longer throw and take down the screen.
 * - `AlarmSetting` is decoded via its legacy mapping, migrating the old `-1` sentinel.
 */
class PresetsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    // ------------------------------------------------------------------ active config

    fun currentConfig(): TimerConfig {
        val raw = prefs.getString(KEY_CURRENT_CONFIG, null) ?: return TimerConfig()
        return decodeConfig(raw) ?: TimerConfig()
    }

    fun saveCurrentConfig(config: TimerConfig) {
        prefs.edit().putString(KEY_CURRENT_CONFIG, encodeConfig(config)).apply()
    }

    // ------------------------------------------------------------------ theme

    fun isDarkTheme(): Boolean = prefs.getBoolean(KEY_DARK_THEME, true)

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    // ------------------------------------------------------------------ encoding

    private fun encodeConfig(config: TimerConfig): String = JSONObject().apply {
        put(FIELD_WORK_MINUTES, config.workMinutes)
        put(FIELD_WORK_SECONDS, config.workSeconds)
        put(FIELD_BREAK_MINUTES, config.breakMinutes)
        put(FIELD_BREAK_SECONDS, config.breakSeconds)
        put(FIELD_LOOPS, config.loops)
        put(FIELD_USE_ALARM, config.alarm.toLegacyUseAlarm())
        put(FIELD_ALARM_DURATION, config.alarm.toLegacyDuration())
        put(FIELD_AUTO_START, config.autoStart)
    }.toString()

    /** Returns null when [raw] cannot be understood at all; never throws. */
    private fun decodeConfig(raw: String): TimerConfig? = try {
        val json = JSONObject(raw)
        val defaults = TimerConfig()
        TimerConfig.coerced(
            workMinutes = json.optInt(FIELD_WORK_MINUTES, defaults.workMinutes),
            workSeconds = json.optInt(FIELD_WORK_SECONDS, defaults.workSeconds),
            breakMinutes = json.optInt(FIELD_BREAK_MINUTES, defaults.breakMinutes),
            breakSeconds = json.optInt(FIELD_BREAK_SECONDS, defaults.breakSeconds),
            loops = json.optInt(FIELD_LOOPS, defaults.loops),
            alarm = AlarmSetting.fromLegacy(
                useAlarm = json.optBoolean(FIELD_USE_ALARM, false),
                alarmDuration = json.optInt(FIELD_ALARM_DURATION, 0)
            ),
            autoStart = json.optBoolean(FIELD_AUTO_START, defaults.autoStart)
        )
    } catch (e: Exception) {
        Log.w(TAG, "Discarding unreadable config", e)
        null
    }

    private fun migrateIfNeeded() {
        val stored = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (stored == SCHEMA_VERSION) return

        // v0 -> v1: the legacy key held the same JSON shape, so adopt it as the active config.
        if (stored == 0) {
            val legacy = prefs.getString(LEGACY_KEY_CURRENT_SETTINGS, null)
            if (legacy != null && !prefs.contains(KEY_CURRENT_CONFIG)) {
                decodeConfig(legacy)?.let { saveCurrentConfig(it) }
            }
        }
        prefs.edit().putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION).apply()
    }

    private companion object {
        const val TAG = "PresetsRepository"
        const val PREFS_NAME = "looptimer_presets"
        const val SCHEMA_VERSION = 1

        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_CURRENT_CONFIG = "current_config"
        const val KEY_DARK_THEME = "dark_theme"
        const val LEGACY_KEY_CURRENT_SETTINGS = "current_settings"

        const val FIELD_WORK_MINUTES = "workMinutes"
        const val FIELD_WORK_SECONDS = "workSeconds"
        const val FIELD_BREAK_MINUTES = "breakMinutes"
        const val FIELD_BREAK_SECONDS = "breakSeconds"
        const val FIELD_LOOPS = "loops"
        const val FIELD_USE_ALARM = "useAlarm"
        const val FIELD_ALARM_DURATION = "alarmDuration"
        const val FIELD_AUTO_START = "autoStart"
    }
}
