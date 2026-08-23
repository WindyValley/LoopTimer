package com.looptimer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test that proves the unit-test source set is wired up and executing.
 * Real behavioural coverage arrives with the domain layer (TimerEngineTest).
 */
class BuildConfigSmokeTest {

    @Test
    fun `version name is populated from gradle properties`() {
        assertTrue(
            "VERSION_NAME should look like MAJOR.MINOR.PATCH but was '${BuildConfig.VERSION_NAME}'",
            Regex("""^\d+\.\d+\.\d+(-debug)?$""").matches(BuildConfig.VERSION_NAME)
        )
    }
}
