package com.pianostream.core.data.library.model

/**
 * One track on the artist page: the [Track] plus the disc it lives on.
 *
 * [indexInCue] is the track's 0-based position inside its disc's `ORDER BY
 * trackNo` list — the cue ordinal `LibraryHasher.mediaId(folderPath,
 * indexInCue)` expects (CC2), which a plain `Track` row doesn't carry.
 * `discTitle`/`discArtworkUri` spare the UI a second lookup per row.
 */
public data class ArtistTrack(
    val track: Track,
    val discTitle: String,
    val discFolderPath: String,
    val discArtworkUri: String?,
    val indexInCue: Int,
)
