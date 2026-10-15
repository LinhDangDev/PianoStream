package com.pianostream.core.data.playback.model

/** Outcome of `PlaybackRepository.mergePlaylist` — what the merge appended. */
public data class PlaylistMergeResult(
    /** Source tracks written onto the target's tail. */
    val added: Int,
    /** Source tracks skipped because their mediaId already sat in target. */
    val skipped: Int,
)
