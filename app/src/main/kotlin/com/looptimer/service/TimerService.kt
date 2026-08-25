package com.looptimer.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.looptimer.data.PresetsRepository
import com.looptimer.data.TimerSnapshot
import com.looptimer.data.TimerStateStore
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerState
import com.looptimer.util.Logger
import kotlin.math.abs
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
 * audible, which is genuine user-visible activity.
 *
 * A run in progress is snapshotted to [TimerStateStore] on every change, so the timer can be
 * re-adopted after the process is killed instead of silently resetting.
 */
class TimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob())
    private lateinit var controller: TimerController
    private lateinit var alarm: AlarmController
    private lateinit var stateStore: TimerStateStore
    private lateinit var notifier: TimerNotifier
    private var observerJob: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()

        notifier = TimerNotifier(this)
        notifier.createChannels()
        stateStore = TimerStateStore(applicationContext)
        alarm = AlarmController(applicationContext)
        controller = TimerController(
            scope = serviceScope,
            clock = ElapsedClock.SYSTEM,
            scheduler = AlarmManagerDeadlineScheduler(applicationContext),
            // Start from what the user last configured, so the notification and the UI agree
            // even before the activity binds.
            initialConfig = PresetsRepository.getInstance(applicationContext).currentConfig()
        )
        restoreRunInProgress()
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
            Logger.w(TAG, "Recreated by system with null intent; re-publishing restored state")
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
                controller.reset()
                stopSelf()
            }
            else -> Logger.w(TAG, "Unknown action: ${intent.action}")
        }

        // Not sticky: an empty redelivery carries no timer state, and the AlarmManager deadline
        // plus the persisted snapshot already cover recovery.
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

    // ------------------------------------------------------------------ restore

    /**
     * Re-adopts a run that was interrupted by process death.
     *
     * Without this the service rebuilt itself from the configuration alone and jumped back to the
     * full duration, while the pending `AlarmManager` wake-up still pointed at the original
     * boundary - so the restored UI disagreed with the alarm that was about to fire.
     */
    private fun restoreRunInProgress() {
        val snapshot = stateStore.load() ?: return

        // elapsedRealtime restarts at zero on boot, so a stored monotonic deadline is only
        // meaningful if the device has not rebooted since it was written.
        if (snapshot.deadlineElapsedMs != null && !isMonotonicClockConsistent(snapshot)) {
            Logger.w(TAG, "Discarding snapshot written before a reboot")
            stateStore.clear()
            return
        }

        runCatching { controller.restore(snapshot.toState(), snapshot.deadlineElapsedMs) }
            .onFailure {
                Logger.w(TAG, "Snapshot could not be applied; starting fresh", it)
                stateStore.clear()
            }
    }

    private fun isMonotonicClockConsistent(snapshot: TimerSnapshot): Boolean {
        val deadlineElapsed = snapshot.deadlineElapsedMs ?: return false
        val deadlineWall = snapshot.deadlineWallClockMs ?: return false
        val impliedWall = System.currentTimeMillis() +
            (deadlineElapsed - SystemClock.elapsedRealtime())
        return abs(impliedWall - deadlineWall) < CLOCK_DRIFT_TOLERANCE_MS
    }

    // ------------------------------------------------------------------ reactions

    private fun onStateChanged(state: TimerState, deadlineElapsedMs: Long?) {
        // Persist first, so a kill immediately after this point still leaves a usable snapshot.
        persist(state, deadlineElapsedMs)

        when (state.runState) {
            RunState.RUNNING, RunState.PAUSED, RunState.AWAITING_CONFIRMATION ->
                notify(TimerNotifications.ID_ONGOING, notifier.ongoing(state, deadlineElapsedMs))
            // Nothing to count: withdraw the notification entirely.
            RunState.IDLE, RunState.COMPLETED -> {
                leaveForeground()
                notificationManager()?.cancel(TimerNotifications.ID_ONGOING)
            }
        }
    }

    private fun persist(state: TimerState, deadlineElapsedMs: Long?) {
        if (state.runState == RunState.IDLE || state.runState == RunState.COMPLETED) {
            stateStore.clear()
            return
        }
        stateStore.save(
            TimerSnapshot.of(
                state = state,
                deadlineElapsedMs = deadlineElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                nowWallClockMs = System.currentTimeMillis()
            )
        )
    }

    private fun onEvent(event: TimerEvent) {
        when (event) {
            is TimerEvent.StartAlarm -> {
                // Audible output is user-visible work, so it justifies a foreground slot.
                enterForegroundForAlarm()
                alarm.start(event.setting)
            }
            is TimerEvent.StopAlarm -> {
                alarm.stop()
                leaveForeground()
            }
            is TimerEvent.PhaseEnded ->
                notify(TimerNotifications.ID_PHASE_END, notifier.phaseEnd(event))
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun notify(id: Int, notification: Notification) {
        try {
            notificationManager()?.notify(id, notification)
        } catch (e: SecurityException) {
            Logger.w(TAG, "Notification permission not granted", e)
        }
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun enterForegroundForAlarm() {
        if (isForeground) return
        val state = controller.state.value
        val notification = notifier.ongoing(state, controller.deadlineElapsedMs.value)
        try {
            startForeground(TimerNotifications.ID_ONGOING, notification)
            isForeground = true
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when started from the
            // background. Degrade to a plain notification rather than crashing.
            Logger.w(TAG, "Could not enter foreground; alarm continues without it", e)
            notify(TimerNotifications.ID_ONGOING, notification)
        }
    }

    private fun leaveForeground() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_DETACH)
        isForeground = false
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

        /** Tolerance when checking whether the monotonic clock survived since the snapshot. */
        private const val CLOCK_DRIFT_TOLERANCE_MS = 5_000L

        const val ACTION_START = "com.looptimer.action.START"
        const val ACTION_PAUSE = "com.looptimer.action.PAUSE"
        const val ACTION_TOGGLE = "com.looptimer.action.TOGGLE"
        const val ACTION_SKIP = "com.looptimer.action.SKIP"
        const val ACTION_RESET = "com.looptimer.action.RESET"
        const val ACTION_STOP_ALARM = "com.looptimer.action.STOP_ALARM"
        const val ACTION_SHUTDOWN = "com.looptimer.action.SHUTDOWN"
        const val ACTION_REFRESH = "com.looptimer.action.REFRESH"
        const val ACTION_DEADLINE_REACHED = "com.looptimer.action.DEADLINE_REACHED"

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
