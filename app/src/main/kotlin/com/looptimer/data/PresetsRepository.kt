package com.looptimer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerPreset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for presets, the active timer configuration, and the theme choice.
 *
 * Improvements over the previous `PresetsManager`:
 * - **Observable.** State is published through [presets] / [activeConfig] instead of screens
 *   polling a `settings_timestamp` on every `ON_RESUME` and calling `recreate()` to refresh.
 * - **Atomic.** Mutations are serialised under a lock and written from an in-memory snapshot,
 *   so concurrent read-modify-write cycles can no longer lose an update.
 * - **Fault tolerant.** Decoding uses `opt*` with defaults and clamps values, and a malformed
 *   record is skipped rather than thrown. Previously one bad entry crashed the whole list,
 *   because `getAllPresets` used throwing `get*` calls with no surrounding try/catch.
 * - **Versioned.** [SCHEMA_VERSION] plus a migration hook, including the `alarmDuration = -1`
 *   sentinel to [AlarmSetting.Forever].
 * - The home screen's configuration is persisted on every edit; it used to be written only when
 *   a preset was selected, so manual edits vanished when the process died.
 */
class PresetsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Guards read-modify-write sequences so concurrent mutations cannot interleave. */
    private val lock = Any()

    private val _presets = MutableStateFlow<List<TimerPreset>>(emptyList())
    val presets: StateFlow<List<TimerPreset>> = _presets.asStateFlow()

    private val _activeConfig = MutableStateFlow(TimerConfig())
    val activeConfig: StateFlow<TimerConfig> = _activeConfig.asStateFlow()

    private val _selectedPresetId = MutableStateFlow<String?>(null)
    val selectedPresetId: StateFlow<String?> = _selectedPresetId.asStateFlow()

    init {
        migrateIfNeeded()
        _presets.value = readPresets()
        _activeConfig.value = readActiveConfig()
        _selectedPresetId.value = prefs.getString(KEY_SELECTED_PRESET, null)
    }

    // ------------------------------------------------------------------ active config

    fun currentConfig(): TimerConfig = _activeConfig.value

    fun saveCurrentConfig(config: TimerConfig) {
        synchronized(lock) {
            prefs.edit().putString(KEY_ACTIVE_CONFIG, encodeConfig(config).toString()).apply()
            _activeConfig.value = config
        }
    }

    // ------------------------------------------------------------------ presets

    fun getPreset(id: String): TimerPreset? = _presets.value.find { it.id == id }

    fun addPreset(name: String, config: TimerConfig): TimerPreset {
        val preset = TimerPreset(
            id = UUID.randomUUID().toString(),
            name = sanitiseName(name),
            config = config
        )
        synchronized(lock) {
            // Write from the in-memory snapshot so a concurrent add cannot clobber this one.
            writePresets(_presets.value + preset)
        }
        return preset
    }

    fun updatePreset(preset: TimerPreset) {
        synchronized(lock) {
            val updated = _presets.value.map {
                if (it.id == preset.id) preset.copy(name = sanitiseName(preset.name)) else it
            }
            writePresets(updated)
        }
    }

    fun deletePreset(id: String) {
        synchronized(lock) {
            writePresets(_presets.value.filterNot { it.id == id })
            if (_selectedPresetId.value == id) {
                prefs.edit().remove(KEY_SELECTED_PRESET).apply()
                _selectedPresetId.value = null
            }
        }
    }

    /** Selects [preset] and adopts its configuration as the active one. */
    fun selectPreset(preset: TimerPreset) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_SELECTED_PRESET, preset.id)
                .putString(KEY_ACTIVE_CONFIG, encodeConfig(preset.config).toString())
                .apply()
            _selectedPresetId.value = preset.id
            _activeConfig.value = preset.config
        }
    }

    // ------------------------------------------------------------------ theme

    fun isDarkTheme(): Boolean = prefs.getBoolean(KEY_DARK_THEME, true)

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    // ------------------------------------------------------------------ storage

    private fun writePresets(presets: List<TimerPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                encodeConfig(preset.config).apply {
                    put(FIELD_ID, preset.id)
                    put(FIELD_NAME, preset.name)
                }
            )
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
        _presets.value = presets
    }

    private fun readPresets(): List<TimerPreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            // Skip unreadable entries instead of letting one bad record fail the whole list.
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                decodePreset(obj)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preset list unreadable; starting empty", e)
            emptyList()
        }
    }

    private fun readActiveConfig(): TimerConfig {
        val raw = prefs.getString(KEY_ACTIVE_CONFIG, null) ?: return TimerConfig()
        return try {
            decodeConfig(JSONObject(raw))
        } catch (e: Exception) {
            Log.w(TAG, "Active config unreadable; using defaults", e)
            TimerConfig()
        }
    }

    // ------------------------------------------------------------------ encoding

    private fun encodeConfig(config: TimerConfig): JSONObject = JSONObject().apply {
        put(FIELD_WORK_MINUTES, config.workMinutes)
        put(FIELD_WORK_SECONDS, config.workSeconds)
        put(FIELD_BREAK_MINUTES, config.breakMinutes)
        put(FIELD_BREAK_SECONDS, config.breakSeconds)
        put(FIELD_LOOPS, config.loops)
        // Kept in the legacy shape so an older build can still read what we write.
        put(FIELD_USE_ALARM, config.alarm.toLegacyUseAlarm())
        put(FIELD_ALARM_DURATION, config.alarm.toLegacyDuration())
        put(FIELD_AUTO_START, config.autoStart)
    }

    /** Never throws: unknown or out-of-range fields fall back to defaults and are clamped. */
    private fun decodeConfig(json: JSONObject): TimerConfig {
        val defaults = TimerConfig()
        return TimerConfig.coerced(
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
    }

    private fun decodePreset(json: JSONObject): TimerPreset? {
        val id = json.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return null
        val name = json.optString(FIELD_NAME).takeIf { it.isNotBlank() } ?: return null
        return TimerPreset(id = id, name = sanitiseName(name), config = decodeConfig(json))
    }

    private fun sanitiseName(name: String): String =
        name.trim().take(TimerPreset.MAX_NAME_LENGTH)

    private fun migrateIfNeeded() {
        val stored = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (stored >= SCHEMA_VERSION) return

        if (stored == 0) {
            // v0 -> v1: adopt the legacy active-settings key, which used the same field shape.
            val legacy = prefs.getString(LEGACY_KEY_CURRENT_SETTINGS, null)
            if (legacy != null && !prefs.contains(KEY_ACTIVE_CONFIG)) {
                runCatching { decodeConfig(JSONObject(legacy)) }
                    .onSuccess {
                        prefs.edit()
                            .putString(KEY_ACTIVE_CONFIG, encodeConfig(it).toString())
                            .apply()
                    }
                    .onFailure { Log.w(TAG, "Could not migrate legacy settings", it) }
            }
            // Preset records keep their key and field names, so they need no rewrite; the
            // alarmDuration = -1 sentinel is handled by AlarmSetting.fromLegacy on read.
        }
        prefs.edit().putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION).apply()
    }

    internal companion object {
        private const val TAG = "PresetsRepository"

        @Volatile
        private var instance: PresetsRepository? = null

        /**
         * Returns the process-wide instance.
         *
         * Shared deliberately: the in-memory [StateFlow]s are the observable source of truth, so
         * a per-activity instance would leave each screen with its own private copy and updates
         * would not propagate.
         */
        fun getInstance(context: Context): PresetsRepository =
            instance ?: synchronized(this) {
                instance ?: PresetsRepository(context.applicationContext).also { instance = it }
            }

        const val PREFS_NAME = "looptimer_presets"
        const val SCHEMA_VERSION = 1

        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_PRESETS = "presets"
        const val KEY_SELECTED_PRESET = "selected_preset"
        const val KEY_ACTIVE_CONFIG = "active_config"
        const val KEY_DARK_THEME = "dark_theme"
        const val LEGACY_KEY_CURRENT_SETTINGS = "current_settings"

        const val FIELD_ID = "id"
        const val FIELD_NAME = "name"
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
