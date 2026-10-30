package com.looptimer.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerState
import com.looptimer.ui.theme.LoopTimerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders [HomeScreen] for the finished state.
 *
 * The state machine that produces COMPLETED is exhaustively covered by JVM unit tests; this
 * instrumentation test pins the *presentation* that those tests cannot see: the completion
 * banner, the "restart" primary action, and the disabled skip action.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenCompletedTest {

    @get:Rule
    val compose = createComposeRule()

    private val completed = TimerState(
        phase = Phase.WORK,
        currentLoop = 2,
        remainingSeconds = 0,
        runState = RunState.COMPLETED,
        totalLoops = 2
    )

    @Test
    fun completed_state_shows_done_banner_and_restart() {
        compose.setContent {
            LoopTimerTheme(darkTheme = true) {
                HomeScreen(
                    config = TimerConfig(workMinutes = 25, loops = 2),
                    timer = completed,
                    alarmRinging = false,
                    onToggleTheme = {},
                    onConfigChange = {},
                    onToggleRun = {},
                    onReset = {},
                    onSkip = {},
                    onStopAlarm = {},
                    onRestart = {}
                )
            }
        }

        compose.onNodeWithText("全部完成").assertIsDisplayed()
        compose.onNodeWithText("重新开始").assertIsDisplayed().assertIsEnabled()
        // Advancing past the end is meaningless, so skip is disabled in the completed state.
        compose.onNodeWithText("跳过").assertIsNotEnabled()
        // The running-only controls are absent.
        compose.onNodeWithText("暂停").assertDoesNotExist()
    }

    @Test
    fun running_state_uses_pause_action_and_keeps_skip_enabled() {
        val running = TimerState(
            phase = Phase.WORK,
            currentLoop = 1,
            remainingSeconds = 1500,
            runState = RunState.RUNNING,
            totalLoops = 4
        )
        compose.setContent {
            LoopTimerTheme(darkTheme = false) {
                HomeScreen(
                    config = TimerConfig(),
                    timer = running,
                    alarmRinging = false,
                    onToggleTheme = {},
                    onConfigChange = {},
                    onToggleRun = {},
                    onReset = {},
                    onSkip = {},
                    onStopAlarm = {},
                    onRestart = {}
                )
            }
        }

        compose.onNodeWithText("暂停").assertIsDisplayed()
        compose.onNodeWithText("跳过").assertIsEnabled()
        compose.onNodeWithText("全部完成").assertDoesNotExist()
    }
}
