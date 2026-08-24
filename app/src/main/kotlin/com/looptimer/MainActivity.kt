package com.looptimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.looptimer.data.PresetsRepository
import com.looptimer.domain.TimerState
import com.looptimer.service.TimerService
import com.looptimer.ui.home.HomeScreen
import com.looptimer.ui.home.TimerConnection
import com.looptimer.ui.theme.LoopTimerTheme

/**
 * Hosts the home screen.
 *
 * The activity deliberately owns no timer state: it binds to [TimerService], renders whatever
 * the service publishes, and forwards user intents back as service actions. Previously the
 * countdown, the phase state machine, notification posting and service control all lived inside
 * a single 570-line composable, so the timer died with the UI.
 */
class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notifications are best-effort; nothing to do with the result. */ }

    private lateinit var connection: TimerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connection = TimerConnection(this)
        val presets = PresetsRepository(applicationContext)

        requestNotificationPermissionIfNeeded()

        setContent {
            val controller by connection.controller.collectAsState()
            var darkTheme by remember { mutableStateOf(presets.isDarkTheme()) }

            // Config is persisted locally and pushed into the service once connected, so the
            // screen still renders (and stays editable) before the binding completes.
            var config by remember { mutableStateOf(presets.currentConfig()) }
            LaunchedEffect(controller, config) {
                controller?.updateConfig(config)
            }

            val timer = controller?.state?.collectAsState()?.value
                ?: TimerState.initial(config)
            val ringing = controller?.alarmRinging?.collectAsState()?.value ?: false

            LoopTimerTheme(darkTheme = darkTheme) {
                HomeScreen(
                    config = config,
                    timer = timer,
                    alarmRinging = ringing,
                    isDarkTheme = darkTheme,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        presets.setDarkTheme(darkTheme)
                    },
                    onConfigChange = { updated ->
                        config = updated
                        presets.saveCurrentConfig(updated)
                    },
                    onToggleRun = { TimerService.send(this, TimerService.ACTION_TOGGLE) },
                    onReset = { TimerService.send(this, TimerService.ACTION_RESET) },
                    onSkip = { TimerService.send(this, TimerService.ACTION_SKIP) },
                    onStopAlarm = { TimerService.send(this, TimerService.ACTION_STOP_ALARM) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    override fun onStop() {
        connection.unbind()
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
