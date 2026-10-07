package com.pianostream.core.data.playback.model

/**
 * One play-history row. `play_history` is capped at 500 rows / 90 days
 * (F10/F19) — [com.pianostream.core.data.playback.PlaybackRepository]
 * implementations prune on insert. Denormalized display columns per CC6;
 * `missing` tombstone per B1.
 */
public data class PlayHistoryItem(
    val id: Long = 0,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: MediaSource,
    val playedAt: Long,
    val playedMs: Long,
    val missing: Boolean = false,
)
