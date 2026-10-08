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
)
