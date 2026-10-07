package com.pianostream.core.data.playback.model

/**
 * One favorites row keyed by `mediaId` (a `pianostream://` URI — CC2/CC3).
 * Denormalized display columns per CC6; `missing` tombstone per B1.
 */
public data class FavoriteItem(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: MediaSource,
    val addedAt: Long,
    val missing: Boolean = false,
)
