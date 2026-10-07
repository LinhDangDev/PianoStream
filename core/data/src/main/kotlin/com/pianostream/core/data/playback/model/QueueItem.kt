package com.pianostream.core.data.playback.model

/** Where a mediaId's audio bytes come from. */
public enum class MediaSource { YOUTUBE, LOCAL }

/**
 * One queue entry — a per-track `MediaItem` (CC1: every playlist entry is a
 * single track, never a per-disc `ConcatenatingMediaSource2`). Carries the
 * denormalized display columns (`title`/`artist`/`artworkUri`) so queue rows
 * render without touching `library.db` (CC6: no cross-database joins).
 *
 * `missing` is the post-scan tombstone flag (B1): when a mediaId's source rows
 * are gone from `library.db`, the row is kept with `missing=true` so Phase 4
 * can surface "N favourites no longer in your library" — never silently deleted.
 *
 * `mediaId` is a `pianostream://stream/<videoId>` or
 * `pianostream://local/<contentKey>` URI (CC2/CC3); `itag` never appears in it.
 */
public data class QueueItem(
    val id: Long = 0,
    val position: Int,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: MediaSource,
    val durationMs: Long?,
    val addedAt: Long,
    val missing: Boolean = false,
)
