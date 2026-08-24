package com.looptimer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the end-of-phase alarm.
 *
 * Previously a global `object` mutated from a bare `Thread` and the main thread with no
 * synchronisation, whose worst failure was permanent: if [MediaPlayer] setup threw, the
 * `isAlarmPlaying` flag stayed `true` forever, so the alarm could never sound again and the UI
 * showed "ringing" indefinitely. Here every state change is guarded, and any failure rolls the
 * state back.
 */
class AlarmController(private val context: Context) {

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var playing = false

    val isPlaying: Boolean get() = synchronized(lock) { playing }

    /**
     * Starts sound and vibration for [setting]. A fixed-duration alarm is stopped by the caller
     * (the controller schedules it); this class only owns the hardware.
     */
    fun start(setting: com.looptimer.domain.AlarmSetting) {
        if (!setting.isEnabled) return
        synchronized(lock) {
            if (playing) return
            playing = true
        }

        val soundOk = startSound()
        val vibrateOk = startVibration()

        if (!soundOk && !vibrateOk) {
            // Nothing actually engaged - roll back so a later attempt is not blocked forever.
            Log.w(TAG, "Alarm failed to engage; resetting state")
            stop()
        }
    }

    fun stop() {
        synchronized(lock) { playing = false }
        releasePlayer()
        cancelVibration()
    }

    /** Releases hardware without implying the alarm "finished". */
    fun release() = stop()

    private fun startSound(): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: run {
                Log.w(TAG, "No alarm or notification sound available")
                return false
            }

        return try {
            releasePlayer()
            val created = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    stop()
                    true
                }
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            synchronized(lock) { player = created }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start alarm sound", e)
            releasePlayer()
            false
        }
    }

    private fun startVibration(): Boolean = try {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vib.hasVibrator()) {
            false
        } else {
            vib.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
            synchronized(lock) { vibrator = vib }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not start vibration", e)
        false
    }

    private fun releasePlayer() {
        val existing = synchronized(lock) { player.also { player = null } } ?: return
        try {
            if (existing.isPlaying) existing.stop()
        } catch (e: IllegalStateException) {
            Log.d(TAG, "MediaPlayer already stopped", e)
        }
        try {
            existing.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer release failed", e)
        }
    }

    private fun cancelVibration() {
        val existing = synchronized(lock) { vibrator.also { vibrator = null } } ?: return
        try {
            existing.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Vibration cancel failed", e)
        }
    }

    private companion object {
        const val TAG = "AlarmController"
        val PATTERN = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000)
    }
}
