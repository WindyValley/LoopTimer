package com.looptimer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.looptimer.MainActivity
import com.looptimer.R
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimeRanges
import com.looptimer.domain.TimerState

/**
 * Builds the app's notifications.
 *
 * Extracted from `TimerService` so the service is left coordinating state and this type owns
 * presentation. Channel and id constants live in [TimerNotifications].
 */
class TimerNotifier(private val context: Context) {

    /**
     * The ongoing notification.
     *
     * While running, the remaining time is handed to the platform chronometer via `setWhen` +
     * `setChronometerCountDown`, so the system counts down on its own and this is posted once per
     * phase rather than once per second.
     */
    fun ongoing(state: TimerState, deadlineElapsedMs: Long?): Notification {
        val phaseLabel = context.getString(
            if (state.phase == Phase.WORK) R.string.phase_work else R.string.phase_break
        )
        val title = when (state.runState) {
            RunState.AWAITING_CONFIRMATION ->
                context.getString(R.string.notif_awaiting_title, phaseLabel)
            RunState.PAUSED -> context.getString(R.string.notif_paused_title, phaseLabel)
            else -> context.getString(R.string.notif_running_title, phaseLabel)
        }
        val toggleLabel = if (state.isRunning) R.string.action_pause else R.string.action_start

        return builder(TimerNotifications.CHANNEL_ONGOING)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (state.isRunning && deadlineElapsedMs != null) {
                    // Convert the monotonic deadline onto the wall-clock base the chronometer uses.
                    val deltaMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
                    setWhen(System.currentTimeMillis() + deltaMs)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                    setShowWhen(true)
                    setContentText(
                        context.getString(
                            R.string.notif_loops, state.currentLoop, state.totalLoops
                        )
                    )
                } else {
                    // Paused or awaiting confirmation: no live countdown, so show the figure.
                    setShowWhen(false)
                    setContentText(
                        context.getString(
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
            .addAction(
                0,
                context.getString(toggleLabel),
                serviceAction(TimerService.ACTION_TOGGLE, REQUEST_TOGGLE)
            )
            .addAction(
                0,
                context.getString(R.string.action_skip),
                serviceAction(TimerService.ACTION_SKIP, REQUEST_SKIP)
            )
            .addAction(
                0,
                context.getString(R.string.action_stop),
                serviceAction(TimerService.ACTION_SHUTDOWN, REQUEST_SHUTDOWN)
            )
            .build()
    }

    /** The end-of-phase alert. */
    fun phaseEnd(event: TimerEvent.PhaseEnded): Notification {
        val info = event.info
        val endedLabel = context.getString(
            if (info.endedPhase == Phase.WORK) R.string.phase_work else R.string.phase_break
        )
        val title = if (info.allLoopsComplete) {
            context.getString(R.string.notif_all_done_title)
        } else {
            context.getString(R.string.notif_phase_end_title, endedLabel)
        }
        val body = when {
            info.allLoopsComplete -> context.getString(R.string.notif_all_done_body)
            info.awaitingConfirmation -> context.getString(R.string.notif_tap_to_continue)
            info.endedPhase == Phase.WORK -> context.getString(R.string.notif_take_a_break)
            else -> context.getString(R.string.notif_back_to_work)
        }

        return builder(TimerNotifications.CHANNEL_PHASE_END)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply {
                if (info.awaitingConfirmation) {
                    addAction(
                        0,
                        context.getString(R.string.action_start),
                        serviceAction(TimerService.ACTION_START, REQUEST_START)
                    )
                }
            }
            .build()
    }

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                TimerNotifications.CHANNEL_ONGOING,
                context.getString(R.string.channel_ongoing),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_ongoing_desc)
                setShowBadge(false)
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                TimerNotifications.CHANNEL_PHASE_END,
                context.getString(R.string.channel_phase_end),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_phase_end_desc)
                enableVibration(true)
            }
        )
    }

    private fun builder(channelId: String) =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentIntent(contentIntent())

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CONTENT,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private companion object {
        // Distinct request codes so the notification actions do not collide.
        const val REQUEST_TOGGLE = 1
        const val REQUEST_SKIP = 2
        const val REQUEST_SHUTDOWN = 3
        const val REQUEST_START = 4
        const val REQUEST_CONTENT = 5

        fun formatTime(totalSeconds: Int): String =
            "%02d:%02d".format(
                totalSeconds / TimeRanges.SECONDS_PER_MINUTE,
                totalSeconds % TimeRanges.SECONDS_PER_MINUTE
            )
    }
}
