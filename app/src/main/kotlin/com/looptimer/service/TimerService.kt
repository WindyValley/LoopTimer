package com.looptimer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.looptimer.MainActivity
import com.looptimer.R
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the running timer.
 *
 * The timer used to live in a composable's `remember`, which meant the countdown died with the
 * UI while this service's notification still claimed it was running. Now the service holds the
 * [TimerController] and the UI merely observes [state], so a configuration change or a destroyed
 * activity no longer affects timing.
 *
 * The service is only in the foreground while there is genuinely something to count.
 */
class TimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob())
    private lateinit var controller: TimerController
    private lateinit var alarm: AlarmController
    private var observerJob: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannels()

        alarm = AlarmController(applicationContext)
        controller = TimerController(
            scope = serviceScope,
            clock = ElapsedClock.SYSTEM,
            initialConfig = TimerConfig()
        )
        binder.attach(controller)

        observerJob = serviceScope.launch {
            launch {
                // Keep the ongoing notification in step with the countdown.
                combine(controller.state, controller.config) { state, _ -> state }
                    .collect { onStateChanged(it) }
            }
            launch {
                controller.events.collect(::onEvent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after the process was killed. The old code
        // fell through a `when (intent?.action)` without ever calling startForeground(), which
        // trips ForegroundServiceDidNotStartInTimeException. Handle it explicitly.
        if (intent == null) {
            Log.i(TAG, "Restarted by system with null intent; restoring foreground state")
            ensureForeground(controller.state.value)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                ensureForeground(controller.state.value)
                controller.start()
            }
            ACTION_PAUSE -> controller.pause()
            ACTION_TOGGLE -> controller.toggle()
            ACTION_SKIP -> controller.skip()
            ACTION_RESET -> controller.reset()
            ACTION_STOP_ALARM -> controller.stopAlarm()
            ACTION_SHUTDOWN -> {
                controller.pause()
                controller.stopAlarm()
                leaveForeground(removeNotification = true)
                stopSelf()
            }
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }

        // Deliberately NOT sticky: the timer's authoritative state is not restorable from an
        // empty redelivery, and a sticky restart previously caused the FGS crash above.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        controller.dispose()
        alarm.release()
        serviceScope.cancel()
        binder.detach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ reactions

    private fun onStateChanged(state: TimerState) {
        when (state.runState) {
            RunState.RUNNING, RunState.AWAITING_CONFIRMATION -> ensureForeground(state)
            RunState.PAUSED -> if (isForeground) notify(buildOngoing(state))
            // Nothing to count: stop holding a foreground slot.
            RunState.IDLE, RunState.COMPLETED -> leaveForeground(removeNotification = true)
        }
    }

    private fun onEvent(event: TimerEvent) {
        when (event) {
            is TimerEvent.StartAlarm -> alarm.start(event.setting)
            is TimerEvent.StopAlarm -> alarm.stop()
            is TimerEvent.PhaseEnded -> postPhaseEnd(event)
        }
    }

    // ------------------------------------------------------------------ foreground plumbing

    private fun ensureForeground(state: TimerState) {
        val notification = buildOngoing(state)
        if (isForeground) {
            notify(notification)
            return
        }
        try {
            startForeground(TimerNotifications.ID_ONGOING, notification)
            isForeground = true
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when we are started
            // from the background. Degrade to a plain notification rather than crashing.
            Log.w(TAG, "Could not enter foreground; falling back to a plain notification", e)
            notify(notification)
        }
    }

    private fun leaveForeground(removeNotification: Boolean) {
        if (!isForeground) return
        stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        isForeground = false
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(TimerNotifications.ID_ONGOING, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }

    private fun buildOngoing(state: TimerState): Notification {
        val phaseLabel = getString(
            if (state.phase == Phase.WORK) R.string.phase_work else R.string.phase_break
        )
        val title = when (state.runState) {
            RunState.AWAITING_CONFIRMATION -> getString(R.string.notif_awaiting_title, phaseLabel)
            RunState.PAUSED -> getString(R.string.notif_paused_title, phaseLabel)
            else -> getString(R.string.notif_running_title, phaseLabel)
        }
        val body = getString(
            R.string.notif_running_body,
            TimerService.formatTime(state.remainingSeconds),
            state.currentLoop,
            state.totalLoops
        )

        val toggleLabel = if (state.isRunning) R.string.action_pause else R.string.action_start

        return NotificationCompat.Builder(this, TimerNotifications.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // These actions drive the same controller the UI does, so the notification can no
            // longer disagree with the timer - previously "stop" only killed the service.
            .addAction(0, getString(toggleLabel), serviceAction(ACTION_TOGGLE, 1))
            .addAction(0, getString(R.string.action_skip), serviceAction(ACTION_SKIP, 2))
            .addAction(0, getString(R.string.action_stop), serviceAction(ACTION_SHUTDOWN, 3))
            .build()
    }

    private fun postPhaseEnd(event: TimerEvent.PhaseEnded) {
        val info = event.info
        val endedLabel = getString(
            if (info.endedPhase == Phase.WORK) R.string.phase_work else R.string.phase_break
        )
        val title = when {
            info.allLoopsComplete -> getString(R.string.notif_all_done_title)
            else -> getString(R.string.notif_phase_end_title, endedLabel)
        }
        val body = when {
            info.allLoopsComplete -> getString(R.string.notif_all_done_body)
            info.awaitingConfirmation -> getString(R.string.notif_tap_to_continue)
            info.endedPhase == Phase.WORK -> getString(R.string.notif_take_a_break)
            else -> getString(R.string.notif_back_to_work)
        }

        val notification = NotificationCompat.Builder(this, TimerNotifications.CHANNEL_PHASE_END)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply {
                if (info.awaitingConfirmation) {
                    addAction(0, getString(R.string.action_start), serviceAction(ACTION_START, 4))
                }
            }
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(TimerNotifications.ID_PHASE_END, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                TimerNotifications.CHANNEL_ONGOING,
                getString(R.string.channel_ongoing),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_ongoing_desc)
                setShowBadge(false)
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                TimerNotifications.CHANNEL_PHASE_END,
                getString(R.string.channel_phase_end),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_phase_end_desc)
                enableVibration(true)
            }
        )
    }

    // ------------------------------------------------------------------ binding

    private val binder = TimerBinder()

    /** Exposes the service-owned controller to the UI process. */
    class TimerBinder : android.os.Binder() {
        private val _controller = MutableStateFlow<TimerController?>(null)
        val controller: StateFlow<TimerController?> = _controller.asStateFlow()

        internal fun attach(controller: TimerController) { _controller.value = controller }
        internal fun detach() { _controller.value = null }
    }

    companion object {
        private const val TAG = "TimerService"

        const val ACTION_START = "com.looptimer.action.START"
        const val ACTION_PAUSE = "com.looptimer.action.PAUSE"
        const val ACTION_TOGGLE = "com.looptimer.action.TOGGLE"
        const val ACTION_SKIP = "com.looptimer.action.SKIP"
        const val ACTION_RESET = "com.looptimer.action.RESET"
        const val ACTION_STOP_ALARM = "com.looptimer.action.STOP_ALARM"
        const val ACTION_SHUTDOWN = "com.looptimer.action.SHUTDOWN"

        fun formatTime(totalSeconds: Int): String =
            "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

        /** Starts the service in the foreground, tolerating the background-start restriction. */
        fun send(context: Context, action: String) {
            val intent = Intent(context, TimerService::class.java).setAction(action)
            try {
                if (action == ACTION_START) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not deliver $action", e)
            }
        }
    }
}
