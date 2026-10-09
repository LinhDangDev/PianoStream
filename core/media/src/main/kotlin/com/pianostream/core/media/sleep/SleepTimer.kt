package com.pianostream.core.media.sleep

/**
 * Pinned sleep-timer contract (player-core feature).
 *
 * `AfterMinutes` counts down on the player's 1 s ticker and pauses on
 * expiry; `EndOfTrack` pauses when the current track ends (transition to
 * another item, or `Player.STATE_ENDED`). Re-setting replaces the active
 * timer; cancelling resets [SleepTimerState] to defaults.
 */
public sealed interface SleepTimerMode {
    public data class AfterMinutes(val minutes: Int) : SleepTimerMode
    public data object EndOfTrack : SleepTimerMode
}

public data class SleepTimerState(
    val active: Boolean = false,
    val remainingMs: Long = 0L,
    val mode: SleepTimerMode? = null,
)
