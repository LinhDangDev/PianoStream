package com.pianostream.core.data.playback

import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity

/**
 * Pure merge math for `PlaybackRepository.mergePlaylist` — split out so the
 * ordering/dedupe rules are unit-testable without a database.
 */
internal object PlaylistMerge {

    data class Plan(
        /** Source rows rewritten for the target — positions continue from
         *  the target's current max, source order preserved. */
        val inserts: List<PlaylistItemEntity>,
        /** Source rows skipped as already-present mediaIds. */
        val skipped: Int,
    )

    /**
     * Appends [source] rows after [target]'s tail. A source row is skipped
     * when its mediaId already exists in [target] — or already appeared
     * earlier in [source] itself (the `(playlistId, mediaId)` PK allows a
     * track only once per playlist anyway).
     */
    fun plan(
        targetPlaylistId: Long,
        target: List<PlaylistItemEntity>,
        source: List<PlaylistItemEntity>,
    ): Plan {
        val existing = target.mapTo(HashSet()) { it.mediaId }
        var position = (target.maxOfOrNull { it.position } ?: -1) + 1
        var skipped = 0
        val inserts = source.mapNotNull { row ->
            if (existing.add(row.mediaId)) {
                row.copy(playlistId = targetPlaylistId, position = position++)
            } else {
                skipped++
                null
            }
        }
        return Plan(inserts, skipped)
    }
}
