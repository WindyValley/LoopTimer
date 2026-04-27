package com.looptimer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 计时器预设配置
 */
data class TimerPreset(
    val id: String,
    val name: String,
    val workMinutes: Int,
    val workSeconds: Int,
    val breakMinutes: Int,
    val breakSeconds: Int,
    val loops: Int,
    val useAlarm: Boolean,
    val alarmDuration: Int,
    val autoStart: Boolean
) {
    companion object {
        /**
         * 从 JSON 创建预设
         */
        fun fromJson(json: JSONObject): TimerPreset {
            return TimerPreset(
                id = json.getString("id"),
                name = json.getString("name"),
                workMinutes = json.getInt("workMinutes"),
                workSeconds = json.getInt("workSeconds"),
                breakMinutes = json.getInt("breakMinutes"),
                breakSeconds = json.getInt("breakSeconds"),
                loops = json.getInt("loops"),
                useAlarm = json.getBoolean("useAlarm"),
                alarmDuration = json.getInt("alarmDuration"),
                autoStart = json.getBoolean("autoStart")
            )
        }
    }
    
    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("workMinutes", workMinutes)
            put("workSeconds", workSeconds)
            put("breakMinutes", breakMinutes)
            put("breakSeconds", breakSeconds)
            put("loops", loops)
            put("useAlarm", useAlarm)
            put("alarmDuration", alarmDuration)
            put("autoStart", autoStart)
        }
    }
}

/**
 * 预设管理器 - 负责预设的 CRUD 操作
 */
class PresetsManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 获取所有预设
     */
    fun getAllPresets(): List<TimerPreset> {
        val json = prefs.getString(KEY_PRESETS, "[]") ?: "[]"
        val array = JSONArray(json)
        val presets = mutableListOf<TimerPreset>()
        for (i in 0 until array.length()) {
            presets.add(TimerPreset.fromJson(array.getJSONObject(i)))
        }
        return presets
    }
    
    /**
     * 保存所有预设
     */
    fun saveAllPresets(presets: List<TimerPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }
    
    /**
     * 添加预设
     */
    fun addPreset(preset: TimerPreset) {
        val presets = getAllPresets().toMutableList()
        presets.add(preset)
        saveAllPresets(presets)
    }
    
    /**
     * 更新预设
     */
    fun updatePreset(preset: TimerPreset) {
        val presets = getAllPresets().toMutableList()
        val index = presets.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            presets[index] = preset
            saveAllPresets(presets)
        }
    }
    
    /**
     * 删除预设
     */
    fun deletePreset(id: String) {
        val presets = getAllPresets().toMutableList()
        presets.removeAll { it.id == id }
        saveAllPresets(presets)
    }
    
    /**
     * 根据 ID 获取预设
     */
    fun getPreset(id: String): TimerPreset? {
        return getAllPresets().find { it.id == id }
    }
    
    /**
     * 获取当前选中的预设 ID
     */
    fun getSelectedPresetId(): String? {
        return prefs.getString(KEY_SELECTED_PRESET, null)
    }
    
    /**
     * 设置当前选中的预设
     */
    fun setSelectedPresetId(id: String?) {
        prefs.edit().putString(KEY_SELECTED_PRESET, id).apply()
    }
    
    /**
     * 保存当前计时器设置（当选择预设时调用）
     */
    fun saveCurrentTimerSettings(preset: TimerPreset) {
        prefs.edit()
            .putString(KEY_CURRENT_SETTINGS, preset.toJson().toString())
            .putLong(KEY_SETTINGS_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * 获取当前计时器设置（从 SharedPreferences 加载）
     */
    fun getCurrentTimerSettings(): TimerPreset? {
        val json = prefs.getString(KEY_CURRENT_SETTINGS, null) ?: return null
        return try {
            TimerPreset.fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取设置更新时间戳
     */
    fun getSettingsTimestamp(): Long {
        return prefs.getLong(KEY_SETTINGS_TIMESTAMP, 0L)
    }

    /**
     * 获取主题设置（true=深色，false=浅色）
     */
    fun isDarkTheme(): Boolean {
        return prefs.getBoolean(KEY_DARK_THEME, true)
    }

    /**
     * 保存主题设置
     */
    fun setDarkTheme(isDark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
    }

    companion object {
        private const val PREFS_NAME = "looptimer_presets"
        private const val KEY_PRESETS = "presets"
        private const val KEY_SELECTED_PRESET = "selected_preset"
        private const val KEY_CURRENT_SETTINGS = "current_settings"
        private const val KEY_SETTINGS_TIMESTAMP = "settings_timestamp"
        private const val KEY_DARK_THEME = "dark_theme"
    }
}
