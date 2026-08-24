package com.looptimer.ui.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerState
import com.looptimer.service.TimerController
import com.looptimer.service.TimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the UI to the service-owned [TimerController].
 *
 * The UI never owns timer state; it observes whatever the service publishes and forwards
 * intents back. While unbound, [state] falls back to a derived preview of [config] so the
 * screen can render before the service connects.
 */
class TimerConnection(private val context: Context) : ServiceConnection {

    private val _controller = MutableStateFlow<TimerController?>(null)
    val controller: StateFlow<TimerController?> = _controller.asStateFlow()

    private var bound = false

    fun bind() {
        if (bound) return
        val intent = Intent(context, TimerService::class.java)
        // Create the service so it exists before the user presses start.
        context.startService(intent)
        bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(this) }
        bound = false
        _controller.value = null
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val timerBinder = binder as? TimerService.TimerBinder ?: return
        _controller.value = timerBinder.controller.value
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        _controller.value = null
    }
}

/** What the home screen renders. */
data class HomeUiState(
    val config: TimerConfig,
    val timer: TimerState,
    val alarmRinging: Boolean = false,
    val isDarkTheme: Boolean = true
)
