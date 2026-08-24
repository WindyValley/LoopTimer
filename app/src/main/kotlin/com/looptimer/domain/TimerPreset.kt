package com.looptimer.domain

/**
 * A named, saveable timer configuration.
 *
 * The configuration itself is delegated to [TimerConfig] rather than duplicating its eight
 * fields, so a preset cannot drift out of sync with what the engine actually understands.
 */
data class TimerPreset(
    val id: String,
    val name: String,
    val config: TimerConfig
) {
    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
