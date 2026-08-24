package com.looptimer.service

/** Single registry for every notification channel and id the app uses. */
object TimerNotifications {

    /** Ongoing, low-importance channel for the foreground service notification. */
    const val CHANNEL_ONGOING = "timer_ongoing"

    /** High-importance channel for end-of-phase alerts. */
    const val CHANNEL_PHASE_END = "timer_phase_end"

    /** The foreground service notification. Stable, so updates replace it in place. */
    const val ID_ONGOING = 1001

    /** End-of-phase alert. */
    const val ID_PHASE_END = 1002
}
