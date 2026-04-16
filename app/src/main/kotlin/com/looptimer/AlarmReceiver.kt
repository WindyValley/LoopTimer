package com.looptimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "STOP_ALARM" -> {
                AlarmController.stopAlarm()
            }
            "START_OR_STOP" -> {
                // Stop alarm if playing
                AlarmController.stopAlarm()
                // Start the timer service to resume/start timer
                val serviceIntent = Intent(context, TimerService::class.java).apply {
                    action = "START"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            else -> {
                val duration = intent.getIntExtra("duration", 10)
                AlarmController.startAlarm(context, duration)
            }
        }
    }
}
