package com.looptimer

import android.Manifest
import android.content.Intent
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
import kotlinx.coroutines.delay
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
        val presets = PresetsRepository.getInstance(this)

        requestNotificationPermissionIfNeeded()

        setContent {
            val controller by connection.controller.collectAsState()
            var darkTheme by remember { mutableStateOf(presets.isDarkTheme()) }

            // The repository is the source of truth for configuration, so selecting a preset in
            // the other activity is picked up here without polling a timestamp on resume.
            val config by presets.activeConfig.collectAsState()
            LaunchedEffect(controller, config) {
                controller?.updateConfig(config)
            }

            val timer = controller?.state?.collectAsState()?.value
                ?: TimerState.initial(config)
            val ringing = controller?.alarmRinging?.collectAsState()?.value ?: false

            // The service does no per-second work; this drives the on-screen seconds and exists
            // only while the screen is composed, so nothing ticks once the UI is gone.
            LaunchedEffect(controller, timer.isRunning) {
                if (timer.isRunning) {
                    while (true) {
                        delay(UI_REFRESH_INTERVAL_MS)
                        controller?.refresh()
                    }
                }
            }

            LoopTimerTheme(darkTheme = darkTheme) {
                HomeScreen(
                    config = config,
                    timer = timer,
                    alarmRinging = ringing,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        presets.setDarkTheme(darkTheme)
                    },
                    onConfigChange = presets::saveCurrentConfig,
                    onToggleRun = { TimerService.send(this, TimerService.ACTION_TOGGLE) },
                    onReset = { TimerService.send(this, TimerService.ACTION_RESET) },
                    onSkip = { TimerService.send(this, TimerService.ACTION_SKIP) },
                    onStopAlarm = { TimerService.send(this, TimerService.ACTION_STOP_ALARM) },
                    onOpenPresets = {
                        startActivity(Intent(this, PresetListActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    override fun onResume() {
        super.onResume()
        // Nothing ticks while the UI is away, so re-derive the displayed time from the clock.
        TimerService.send(this, TimerService.ACTION_REFRESH)
    }

    override fun onStop() {
        connection.unbind()
        super.onStop()
    }

    private companion object {
        /** Cadence for redrawing the on-screen seconds; only runs while the UI is visible. */
        const val UI_REFRESH_INTERVAL_MS = 500L
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
