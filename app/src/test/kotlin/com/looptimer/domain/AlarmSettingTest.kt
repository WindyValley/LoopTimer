package com.looptimer.domain

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Covers the alarm model that replaced the `alarmDuration = -1` magic value, including the
 * legacy encoding round-trip that persisted presets depend on.
 */
class AlarmSettingTest {

    @Test
    fun `off is not enabled and fixed or forever are`() {
        assertFalse(AlarmSetting.Off.isEnabled)
        assertTrue(AlarmSetting.Fixed(10).isEnabled)
        assertTrue(AlarmSetting.Forever.isEnabled)
    }

    @Test
    fun `fixed rejects out of range durations`() {
        assertFailsWith<IllegalArgumentException> { AlarmSetting.Fixed(0) }
        assertFailsWith<IllegalArgumentException> { AlarmSetting.Fixed(-1) }
        assertFailsWith<IllegalArgumentException> { AlarmSetting.Fixed(99_999) }
    }

    @Test
    fun `legacy decoding maps the magic values`() {
        // useAlarm=false wins regardless of duration
        assertEquals(AlarmSetting.Off, AlarmSetting.fromLegacy(useAlarm = false, alarmDuration = 30))
        // -1 was the "ring forever" sentinel
        assertEquals(AlarmSetting.Forever, AlarmSetting.fromLegacy(true, -1))
        // 0 duration means nothing to play
        assertEquals(AlarmSetting.Off, AlarmSetting.fromLegacy(true, 0))
        assertEquals(AlarmSetting.Fixed(30), AlarmSetting.fromLegacy(true, 30))
    }

    @Test
    fun `legacy decoding clamps absurd stored durations instead of throwing`() {
        assertEquals(AlarmSetting.Fixed(3600), AlarmSetting.fromLegacy(true, 999_999))
    }

    @Test
    fun `legacy round trip preserves every case`() {
        listOf(AlarmSetting.Off, AlarmSetting.Fixed(45), AlarmSetting.Forever).forEach { original ->
            val restored = AlarmSetting.fromLegacy(
                original.toLegacyUseAlarm(),
                original.toLegacyDuration()
            )
            assertEquals(original, restored, "round trip failed for $original")
        }
    }

    @Test
    fun `forever is representable and distinct from a long fixed duration`() {
        // The preset editor previously clamped to 1..300, making Forever unrepresentable.
        assertTrue(AlarmSetting.Forever != AlarmSetting.Fixed(300))
        assertEquals(-1, AlarmSetting.Forever.toLegacyDuration())
    }
}
