package com.looptimer.service

import android.os.SystemClock

/**
 * Monotonic time source, abstracted so the timing logic can be unit tested without Android.
 *
 * Must be monotonic and unaffected by wall-clock changes; [SystemClock.elapsedRealtime] also
 * keeps counting while the device is asleep, which is what lets the timer stay accurate across
 * screen-off periods.
 */
fun interface ElapsedClock {
    fun elapsedRealtimeMs(): Long

    companion object {
        val SYSTEM = ElapsedClock { SystemClock.elapsedRealtime() }
    }
}
