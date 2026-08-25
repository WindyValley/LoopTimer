package com.looptimer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.looptimer.MainActivity
import com.looptimer.R
import com.looptimer.data.PresetsRepository
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimeRanges
import com.looptimer.domain.TimerState
import com.looptimer.util.Logger
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
 * The timer used to live in a composable's `remember`, which meant the countdown died with the UI
 * while a foreground-service notification still claimed it was running. The service now holds the
 * [TimerController] and the UI merely observes [TimerBinder.controller].
 *
 * Phase boundaries are scheduled with `AlarmManager` and the visible countdown is drawn by the
 * notification's own chronometer, so the service does **no** per-second work and does not need to
 * stay in the foreground merely to count. It only enters the foreground while the alarm is actually
 * audible, which is a genuine user-visible activity.
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
            scheduler = AlarmManagerDeadlineScheduler(applicationContext),
            // Start from what the user last configured, so the notification and the UI agree
            // even before the activity binds.
            initialConfig = PresetsRepository.getInstance(applicationContext).currentConfig()
        )
        binder.attach(controller)

        observerJob = serviceScope.launch {
            launch {
                combine(controller.state, controller.deadlineElapsedMs) { state, deadline ->
                    state to deadline
                }.collect { (state, deadline) -> onStateChanged(state, deadline) }
            }
            launch { controller.events.collect(::onEvent) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system recreated us after the process was killed. The original
        // code fell through `when (intent?.action)` without ever calling startForeground(), which
        // trips ForegroundServiceDidNotStartInTimeException.
        if (intent == null) {
            Logger.w(TAG, "Recreated by system with null intent; re-publishing state")
            controller.refresh()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> controller.start()
            ACTION_PAUSE -> controller.pause()
            ACTION_TOGGLE -> controller.toggle()
            ACTION_SKIP -> controller.skip()
            ACTION_RESET -> controller.reset()
            ACTION_STOP_ALARM -> controller.stopAlarm()
            ACTION_REFRESH -> controller.refresh()
            // Delivered by AlarmManager at a phase boundary.
            ACTION_DEADLINE_REACHED -> controller.onDeadlineReached()
            ACTION_SHUTDOWN -> {
                controller.pause()
                controller.stopAlarm()
                stopSelf()
            }
            else -> Logger.w(TAG, "Unknown action: ${intent.action}")
        }

        // Not sticky: an empty redelivery carries no timer state, and the AlarmManager deadline
        // survives process death independently, so the boundary still fires.
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

    private fun onStateChanged(state: TimerState, deadlineElapsedMs: Long?) {
        when (state.runState) {
            RunState.RUNNING, RunState.PAUSED, RunState.AWAITING_CONFIRMATION ->
                notifyOngoing(state, deadlineElapsedMs)
            // Nothing to count: withdraw the notification entirely.
            RunState.IDLE, RunState.COMPLETED -> cancelOngoing()
        }
    }

    private fun onEvent(event: TimerEvent) {
        when (event) {
            is TimerEvent.StartAlarm -> {
                // The alarm is audible, user-visible work, so it justifies a foreground slot.
                enterForegroundForAlarm()
                alarm.start(event.setting)
            }
            is TimerEvent.StopAlarm -> {
                alarm.stop()
                leaveForeground()
            }
            is TimerEvent.PhaseEnded -> postPhaseEnd(event)
        }
    }

    // ------------------------------------------------------------------ notifications

    /**
     * Builds the ongoing notification.
     *
     * When running, the remaining time is handed to the platform chronometer via [setWhen] +
     * [setChronometerCountDown]: the system counts down on its own, so we post this once per phase
     * instead of once per second.
     */
    private fun buildOngoing(state: TimerState, deadlineElapsedMs: Long?): Notification {
        val phaseLabel = getString(
            if (state.phase == Phase.WORK) R.string.phase_work else R.string.phase_break
        )
        val title = when (state.runState) {
            RunState.AWAITING_CONFIRMATION -> getString(R.string.notif_awaiting_title, phaseLabel)
            RunState.PAUSED -> getString(R.string.notif_paused_title, phaseLabel)
            else -> getString(R.string.notif_running_title, phaseLabel)
        }
        val loops = getString(R.string.notif_loops, state.currentLoop, state.totalLoops)
        val toggleLabel = if (state.isRunning) R.string.action_pause else R.string.action_start

        return NotificationCompat.Builder(this, TimerNotifications.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (state.isRunning && deadlineElapsedMs != null) {
                    // Convert the monotonic deadline to the wall-clock base the chronometer uses.
                    val deltaMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
                    setWhen(System.currentTimeMillis() + deltaMs)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                    setShowWhen(true)
                    setContentText(loops)
                } else {
                    // Paused or awaiting confirmation: no live countdown, so show the figure.
                    setShowWhen(false)
                    setContentText(
                        getString(
                            R.string.notif_running_body,
                            formatTime(state.remainingSeconds),
                            state.currentLoop,
                            state.totalLoops
                        )
                    )
                }
            }
            // These actions drive the same controller the UI does, so the notification can no
            // longer disagree with the timer - previously "stop" only killed the service.
            .addAction(0, getString(toggleLabel), serviceAction(ACTION_TOGGLE, REQUEST_TOGGLE))
            .addAction(0, getString(R.string.action_skip), serviceAction(ACTION_SKIP, REQUEST_SKIP))
            .addAction(
                0,
                getString(R.string.action_stop),
                serviceAction(ACTION_SHUTDOWN, REQUEST_SHUTDOWN)
            )
            .build()
    }

    private fun notifyOngoing(state: TimerState, deadlineElapsedMs: Long?) {
        notify(TimerNotifications.ID_ONGOING, buildOngoing(state, deadlineElapsedMs))
    }

    private fun cancelOngoing() {
        leaveForeground()
        notificationManager()?.cancel(TimerNotifications.ID_ONGOING)
    }

    private fun postPhaseEnd(event: TimerEvent.PhaseEnded) {
        val info = event.info
        val endedLabel = getString(
            if (info.endedPhase == Phase.WORK) R.string.phase_work else R.string.phase_break
        )
        val title = if (info.allLoopsComplete) {
            getString(R.string.notif_all_done_title)
        } else {
            getString(R.string.notif_phase_end_title, endedLabel)
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
                    addAction(
                        0,
                        getString(R.string.action_start),
                        serviceAction(ACTION_START, REQUEST_START)
                    )
                }
            }
            .build()

        notify(TimerNotifications.ID_PHASE_END, notification)
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            notificationManager()?.notify(id, notification)
        } catch (e: SecurityException) {
            Logger.w(TAG, "Notification permission not granted", e)
        }
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    // ------------------------------------------------------------------ foreground (alarm only)

    private fun enterForegroundForAlarm() {
        if (isForeground) return
        val state = controller.state.value
        try {
            startForeground(
                TimerNotifications.ID_ONGOING,
                buildOngoing(state, controller.deadlineElapsedMs.value)
            )
            isForeground = true
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when started from the
            // background. Degrade to a plain notification rather than crashing.
            Logger.w(TAG, "Could not enter foreground; alarm continues without it", e)
            notifyOngoing(state, controller.deadlineElapsedMs.value)
        }
    }

    private fun leaveForeground() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_DETACH)
        isForeground = false
    }

    // ------------------------------------------------------------------ plumbing

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_CONTENT,
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
        val manager = notificationManager() ?: return
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

    /** Exposes the service-owned controller to the UI. */
    class TimerBinder : android.os.Binder() {
        private val _controller = MutableStateFlow<TimerController?>(null)
        val controller: StateFlow<TimerController?> = _controller.asStateFlow()

        internal fun attach(controller: TimerController) { _controller.value = controller }
        internal fun detach() { _controller.value = null }
    }

    companion object {
        private const val TAG = "TimerService"

        // Distinct PendingIntent request codes so the notification actions do not collide.
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_SKIP = 2
        private const val REQUEST_SHUTDOWN = 3
        private const val REQUEST_START = 4
        private const val REQUEST_CONTENT = 5

        const val ACTION_START = "com.looptimer.action.START"
        const val ACTION_PAUSE = "com.looptimer.action.PAUSE"
        const val ACTION_TOGGLE = "com.looptimer.action.TOGGLE"
        const val ACTION_SKIP = "com.looptimer.action.SKIP"
        const val ACTION_RESET = "com.looptimer.action.RESET"
        const val ACTION_STOP_ALARM = "com.looptimer.action.STOP_ALARM"
        const val ACTION_SHUTDOWN = "com.looptimer.action.SHUTDOWN"
        const val ACTION_REFRESH = "com.looptimer.action.REFRESH"
        const val ACTION_DEADLINE_REACHED = "com.looptimer.action.DEADLINE_REACHED"

        fun formatTime(totalSeconds: Int): String =
            "%02d:%02d".format(
                totalSeconds / TimeRanges.SECONDS_PER_MINUTE,
                totalSeconds % TimeRanges.SECONDS_PER_MINUTE
            )

        /** Delivers [action] to the service. */
        fun send(context: Context, action: String) {
            try {
                context.startService(
                    Intent(context, TimerService::class.java).setAction(action)
                )
            } catch (e: Exception) {
                Logger.w(TAG, "Could not deliver $action", e)
            }
        }
    }
}
