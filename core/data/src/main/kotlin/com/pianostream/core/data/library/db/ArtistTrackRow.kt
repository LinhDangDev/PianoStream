package com.pianostream.core.data.library.db

import androidx.room.Embedded
import com.pianostream.core.data.library.db.entity.TrackEntity

/**
 * Joined row for `TrackDao.observeArtistTracks` — one track plus the disc
 * context the artist page needs (folder path seeds `LibraryHasher.mediaId`,
 * title/artwork feed row display). `indexInCue` is the 0-based cue ordinal:
 * the track's position inside its disc's `ORDER BY trackNo` list — the same
 * convention `SearchViewModel.mediaIdFor` relies on (CC2).
 */
data class ArtistTrackRow(
    @Embedded val track: TrackEntity,
    val discFolderPath: String,
    val discTitle: String,
    val discArtworkUri: String?,
    val indexInCue: Int,
)
