package com.looptimer.service

/**
 * Schedules a one-shot wake-up at a monotonic instant.
 *
 * Abstracted so [TimerController] can be driven by `AlarmManager` in production and by a fake in
 * tests. The contract is deliberately minimal: at most one deadline is outstanding at a time, and
 * [schedule] replaces any previous one.
 */
interface DeadlineScheduler {

    /**
     * Requests a callback at [triggerAtElapsedMs], expressed on the same timebase as
     * [ElapsedClock.elapsedRealtimeMs].
     */
    fun schedule(triggerAtElapsedMs: Long)

    /** Cancels any outstanding deadline. */
    fun cancel()

    /** A scheduler that does nothing; used when a controller only needs to compute state. */
    companion object {
        val NONE = object : DeadlineScheduler {
            override fun schedule(triggerAtElapsedMs: Long) = Unit
            override fun cancel() = Unit
        }
    }
}
