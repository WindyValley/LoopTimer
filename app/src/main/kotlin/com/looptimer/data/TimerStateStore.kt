package com.looptimer.data

import android.content.Context
import android.content.SharedPreferences
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.util.Logger
import org.json.JSONObject

/**
 * Persists the state of a run in progress.
 *
 * Kept separate from [PresetsRepository] because it has a different lifetime and different
 * durability needs: it is written on every state change and read exactly once, at service start.
 *
 * Writes use `commit()` rather than `apply()` on purpose. The whole point of this store is to
 * survive the process being killed, and an asynchronous write can be lost precisely when it
 * matters most.
 */
class TimerStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(snapshot: TimerSnapshot) {
        val json = JSONObject().apply {
            put(FIELD_PHASE, snapshot.phase.name)
            put(FIELD_CURRENT_LOOP, snapshot.currentLoop)
            put(FIELD_TOTAL_LOOPS, snapshot.totalLoops)
            put(FIELD_RUN_STATE, snapshot.runState.name)
            put(FIELD_REMAINING, snapshot.remainingSeconds)
            snapshot.deadlineElapsedMs?.let { put(FIELD_DEADLINE_ELAPSED, it) }
            snapshot.deadlineWallClockMs?.let { put(FIELD_DEADLINE_WALL, it) }
            put(FIELD_SAVED_AT, snapshot.savedAtWallClockMs)
        }
        @Suppress("ApplySharedPref")
        prefs.edit().putString(KEY_SNAPSHOT, json.toString()).commit()
    }

    fun clear() {
        @Suppress("ApplySharedPref")
        prefs.edit().remove(KEY_SNAPSHOT).commit()
    }

    /** Returns the stored snapshot, or null when absent or unreadable. */
    fun load(): TimerSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val json = JSONObject(raw)
            TimerSnapshot(
                phase = Phase.valueOf(json.getString(FIELD_PHASE)),
                currentLoop = json.getInt(FIELD_CURRENT_LOOP),
                totalLoops = json.getInt(FIELD_TOTAL_LOOPS),
                runState = RunState.valueOf(json.getString(FIELD_RUN_STATE)),
                remainingSeconds = json.getInt(FIELD_REMAINING),
                deadlineElapsedMs = json.optLong(FIELD_DEADLINE_ELAPSED, -1L).takeIf { it >= 0 },
                deadlineWallClockMs = json.optLong(FIELD_DEADLINE_WALL, -1L).takeIf { it >= 0 },
                savedAtWallClockMs = json.optLong(FIELD_SAVED_AT, 0L)
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Run snapshot unreadable; discarding", e)
            clear()
            null
        }
    }

    private companion object {
        const val TAG = "TimerStateStore"
        const val PREFS_NAME = "looptimer_run_state"

        const val KEY_SNAPSHOT = "snapshot"
        const val FIELD_PHASE = "phase"
        const val FIELD_CURRENT_LOOP = "currentLoop"
        const val FIELD_TOTAL_LOOPS = "totalLoops"
        const val FIELD_RUN_STATE = "runState"
        const val FIELD_REMAINING = "remainingSeconds"
        const val FIELD_DEADLINE_ELAPSED = "deadlineElapsed"
        const val FIELD_DEADLINE_WALL = "deadlineWall"
        const val FIELD_SAVED_AT = "savedAt"
    }
}
