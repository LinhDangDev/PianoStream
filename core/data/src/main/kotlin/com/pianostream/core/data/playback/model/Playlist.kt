package com.pianostream.core.data.playback.model

/**
 * One user playlist as shown in the library list — the row plus its current
 * track count. The playlist's tracks are read back as [QueueItem]s via
 * `observePlaylistTracks` so they can be handed to `onPlayQueue` directly.
 */
public data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackCount: Int,
    /** User-written blurb (Edit details dialog); empty when never set. */
    val description: String = "",
    /**
     * Generated cover: up to 4 artwork URIs taken from the first tracks,
     * refreshed by the repository on every membership change (v3).
     */
    val coverUris: List<String> = emptyList(),
)
