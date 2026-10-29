package com.looptimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.looptimer.data.PresetsRepository
import com.looptimer.service.TimerService
import com.looptimer.ui.home.TimerConnection
import com.looptimer.ui.navigation.AppNavHost

/**
 * The single activity of the app.
 *
 * It only owns the Android-level concerns - the notification permission, binding to
 * [TimerService] while visible, and hosting the Compose navigation graph. All screens and
 * navigation live in the UI layer ([AppNavHost]); previously the home, preset list and preset
 * editor were three separate activities, triplicating theme setup and chaining the back stack.
 *
 * The activity owns no timer state: it binds to the service, which remains the single source
 * of truth, and forwards user intents as service actions.
 */
class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notifications are best-effort; nothing to do with the result. */ }

    private lateinit var connection: TimerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = TimerConnection(this)
        requestNotificationPermissionIfNeeded()
        setContent {
            AppNavHost(
                connection = connection,
                presets = PresetsRepository.getInstance(this)
            )
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
