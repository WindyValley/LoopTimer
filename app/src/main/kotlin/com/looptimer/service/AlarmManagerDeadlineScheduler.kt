package com.looptimer.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.looptimer.util.Logger

/**
 * Schedules phase boundaries with [AlarmManager].
 *
 * This is what lets the app stop running code between boundaries. The countdown itself is rendered
 * by the notification's own chronometer, so nothing needs to tick once per second; we only need to
 * be woken when a phase actually ends.
 *
 * [AlarmManager.setAlarmClock] is used deliberately over `setExactAndAllowWhileIdle`: it is exempt
 * from Doze deferral and from the exact-alarm rate limits, which is the behaviour a user expects
 * from an interval timer. It also surfaces the standard "next alarm" affordance, so the pending
 * wake-up is discoverable rather than hidden. It does **not** create an entry in the user's clock
 * app - that would require `AlarmClock.ACTION_SET_ALARM`, which this app never sends.
 */
class AlarmManagerDeadlineScheduler(
    private val context: Context,
    private val clock: ElapsedClock = ElapsedClock.SYSTEM
) : DeadlineScheduler {

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    override fun schedule(triggerAtElapsedMs: Long) {
        val manager = alarmManager ?: run {
            Logger.w(TAG, "AlarmManager unavailable; phase boundary will not fire")
            return
        }

        // AlarmManager's ALARM_CLOCK / RTC variants take wall-clock time, so convert from the
        // monotonic timebase the timer reasons about.
        val deltaMs = (triggerAtElapsedMs - clock.elapsedRealtimeMs()).coerceAtLeast(0L)
        val triggerAtWallClock = System.currentTimeMillis() + deltaMs

        try {
            if (canScheduleExact(manager)) {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtWallClock, showIntent()),
                    firePendingIntent()
                )
            } else {
                // The user revoked exact alarms. Stay functional with a best-effort wake-up
                // rather than silently never firing.
                Logger.w(TAG, "Exact alarms not permitted; falling back to an inexact wake-up")
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtWallClock,
                    firePendingIntent()
                )
            }
        } catch (e: SecurityException) {
            Logger.w(TAG, "Not allowed to schedule the phase boundary", e)
        }
    }

    override fun cancel() {
        alarmManager?.cancel(firePendingIntent())
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }

    /** Delivered to the service when a phase boundary is reached. */
    private fun firePendingIntent(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_FIRE,
        Intent(context, TimerService::class.java).setAction(TimerService.ACTION_DEADLINE_REACHED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Opened if the user taps the system's "next alarm" affordance. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SHOW,
        Intent(context, com.looptimer.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private companion object {
        const val TAG = "AlarmScheduler"
        const val REQUEST_FIRE = 100
        const val REQUEST_SHOW = 101
    }
}
