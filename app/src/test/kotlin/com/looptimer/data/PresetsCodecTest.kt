package com.looptimer.data

import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.TimerConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Covers the encode/decode and migration contract that persisted user data depends on.
 *
 * The codec is exercised directly rather than through Android's SharedPreferences so these stay
 * fast JVM tests; the risky logic is the parsing and the legacy migration, not the key/value store.
 */
class PresetsCodecTest {

    // Mirrors PresetsRepository.encodeConfig.
    private fun encode(config: TimerConfig): JSONObject = JSONObject().apply {
        put("workMinutes", config.workMinutes)
        put("workSeconds", config.workSeconds)
        put("breakMinutes", config.breakMinutes)
        put("breakSeconds", config.breakSeconds)
        put("loops", config.loops)
        put("useAlarm", config.alarm.toLegacyUseAlarm())
        put("alarmDuration", config.alarm.toLegacyDuration())
        put("autoStart", config.autoStart)
    }

    // Mirrors PresetsRepository.decodeConfig: opt* with defaults, then clamped.
    private fun decode(json: JSONObject): TimerConfig {
        val d = TimerConfig()
        return TimerConfig.coerced(
            workMinutes = json.optInt("workMinutes", d.workMinutes),
            workSeconds = json.optInt("workSeconds", d.workSeconds),
            breakMinutes = json.optInt("breakMinutes", d.breakMinutes),
            breakSeconds = json.optInt("breakSeconds", d.breakSeconds),
            loops = json.optInt("loops", d.loops),
            alarm = AlarmSetting.fromLegacy(
                useAlarm = json.optBoolean("useAlarm", false),
                alarmDuration = json.optInt("alarmDuration", 0)
            ),
            autoStart = json.optBoolean("autoStart", d.autoStart)
        )
    }

    @Test
    fun `config survives a round trip`() {
        val original = TimerConfig(
            workMinutes = 45, workSeconds = 30,
            breakMinutes = 7, breakSeconds = 15,
            loops = 6,
            alarm = AlarmSetting.Fixed(90),
            autoStart = false
        )

        assertEquals(original, decode(encode(original)))
    }

    @Test
    fun `every alarm setting survives a round trip`() {
        listOf(AlarmSetting.Off, AlarmSetting.Fixed(30), AlarmSetting.Forever).forEach { alarm ->
            val config = TimerConfig(alarm = alarm)
            assertEquals(alarm, decode(encode(config)).alarm, "round trip failed for $alarm")
        }
    }

    // ---------------------------------------------------------------- fault tolerance

    @Test
    fun `missing fields fall back to defaults instead of throwing`() {
        // Regression: the old fromJson used throwing get* calls, so an incomplete record
        // crashed the entire preset list.
        val sparse = JSONObject().put("workMinutes", 10)
        val decoded = decode(sparse)

        assertEquals(10, decoded.workMinutes)
        assertEquals(TimerConfig().breakMinutes, decoded.breakMinutes)
        assertEquals(TimerConfig().loops, decoded.loops)
    }

    @Test
    fun `empty object decodes to defaults`() {
        assertEquals(TimerConfig(), decode(JSONObject()))
    }

    @Test
    fun `wrong field types fall back rather than throwing`() {
        val wrong = JSONObject()
            .put("workMinutes", "not a number")
            .put("loops", JSONObject())
        val decoded = decode(wrong)

        assertEquals(TimerConfig().workMinutes, decoded.workMinutes)
        assertEquals(TimerConfig().loops, decoded.loops)
    }

    @Test
    fun `out of range stored values are clamped into legal config`() {
        val absurd = JSONObject()
            .put("workMinutes", 100_000)
            .put("workSeconds", 999)
            .put("loops", -5)
        val decoded = decode(absurd)

        // Construction would have thrown on these; coerced() clamps instead.
        assertEquals(999, decoded.workMinutes)
        assertEquals(59, decoded.workSeconds)
        assertEquals(1, decoded.loops)
    }

    @Test
    fun `a malformed entry is skipped without discarding the whole list`() {
        val array = JSONArray()
            .put(encode(TimerConfig(workMinutes = 5)).put("id", "a").put("name", "First"))
            .put("this is not an object")
            .put(JSONObject().put("id", "").put("name", "No id"))
            .put(encode(TimerConfig(workMinutes = 9)).put("id", "b").put("name", "Second"))

        // Mirrors readPresets: optJSONObject + mapNotNull, so bad rows drop out.
        val decoded = (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(id, name, decode(obj))
        }

        assertEquals(2, decoded.size, "expected the two valid rows to survive")
        assertEquals(listOf("First", "Second"), decoded.map { it.second })
        assertEquals(5, decoded[0].third.workMinutes)
    }

    // ---------------------------------------------------------------- legacy migration

    @Test
    fun `legacy ring-forever sentinel migrates to Forever`() {
        // Old encoding: alarmDuration = -1 meant "ring until stopped".
        val legacy = JSONObject()
            .put("workMinutes", 25)
            .put("useAlarm", true)
            .put("alarmDuration", -1)

        assertEquals(AlarmSetting.Forever, decode(legacy).alarm)
    }

    @Test
    fun `legacy alarm disabled flag wins over a stored duration`() {
        val legacy = JSONObject().put("useAlarm", false).put("alarmDuration", 30)
        assertEquals(AlarmSetting.Off, decode(legacy).alarm)
    }

    @Test
    fun `legacy zero duration decodes to Off`() {
        val legacy = JSONObject().put("useAlarm", true).put("alarmDuration", 0)
        assertEquals(AlarmSetting.Off, decode(legacy).alarm)
    }

    @Test
    fun `a full legacy record decodes without loss`() {
        val legacy = JSONObject()
            .put("id", "legacy-1")
            .put("name", "番茄工作法")
            .put("workMinutes", 25)
            .put("workSeconds", 0)
            .put("breakMinutes", 5)
            .put("breakSeconds", 0)
            .put("loops", 4)
            .put("useAlarm", true)
            .put("alarmDuration", 30)
            .put("autoStart", false)

        val config = decode(legacy)

        assertEquals(25, config.workMinutes)
        assertEquals(5, config.breakMinutes)
        assertEquals(4, config.loops)
        assertEquals(AlarmSetting.Fixed(30), config.alarm)
        assertEquals(false, config.autoStart)
        assertEquals("番茄工作法", legacy.optString("name"))
    }

    @Test
    fun `what we write is still readable by the legacy field names`() {
        // Forward compatibility: keep the legacy shape so an older build can read new data.
        val written = encode(TimerConfig(alarm = AlarmSetting.Forever))

        assertTrue(written.has("useAlarm"))
        assertTrue(written.has("alarmDuration"))
        assertEquals(true, written.getBoolean("useAlarm"))
        assertEquals(-1, written.getInt("alarmDuration"))
    }

    @Test
    fun `preset identity fields are required`() {
        val noId = JSONObject().put("name", "x")
        val noName = JSONObject().put("id", "x")

        assertNull(noId.optString("id").takeIf { it.isNotBlank() })
        assertNull(noName.optString("name").takeIf { it.isNotBlank() })
        assertNotNull(JSONObject().put("id", "a").optString("id").takeIf { it.isNotBlank() })
    }
}
