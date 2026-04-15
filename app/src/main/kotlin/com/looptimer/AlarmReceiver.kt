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
            else -> {
                val duration = intent.getIntExtra("duration", 10)
                AlarmController.startAlarm(context, duration)
            }
        }
    }
}
