package com.looptimer

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.looptimer.domain.TimerConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumentation test for the single-activity app: launches [MainActivity],
 * verifies the default timer renders, and exercises in-process navigation to the preset list
 * and the system back gesture. Guards the B2 migration (three activities -> one NavHost): a
 * regression in a route or the back stack fails here instead of in manual testing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {

    @get:Rule
    val grantPermission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val defaultWorkMinutes = TimerConfig().workMinutes

    @Test
    fun launches_home_with_default_duration() {
        compose.onNodeWithText("%02d:00".format(defaultWorkMinutes)).assertIsDisplayed()
        compose.onNodeWithText("预设管理").assertIsDisplayed()
    }

    @Test
    fun opens_preset_list_then_back_returns_home() {
        compose.onNodeWithText("预设管理").performClick()
        // The list top bar's back affordance exists only on that destination, regardless of
        // whether the device already has presets, so it proves navigation happened.
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()

        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("%02d:00".format(defaultWorkMinutes)).assertIsDisplayed()
    }
}
