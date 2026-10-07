package com.pianostream.core.media.queue

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.local.api.LocalTrackWindow
import com.pianostream.core.local.api.LocalTrackWindowSource
import com.pianostream.core.media.local.LocalMediaItemFactory

/**
 * Rebuilds the durable queue as one `MediaItem` **per track** (CC1 — no
 * `ConcatenatingMediaSource2` anywhere, so move/seek/resume-by-index are all
 * per-track).
 *
 * Local rows resolve through [LocalTrackWindowSource] — dead keys are omitted
 * from the assembled list but counted in [AssembledQueue.missingLocal] so the
 * reconcile path tombstones them (B1), never silently shortening the queue.
 */
class QueueAssembler(
    private val localItems: LocalMediaItemFactory,
    private val windowSource: LocalTrackWindowSource,
    /** disc artwork resolver: contentKey → (discContentKey, artworkDocUri, flacDurationMs). */
    private val artworkOf: suspend (contentKey: String) -> DiscArtwork?,
) {

    data class DiscArtwork(
        val discId: String,
        val artworkUri: String?,
        val flacDurationMs: Long?,
    )

    data class AssembledQueue(
        /** MediaItems parallel to the surviving queue rows, in order. */
        val items: List<MediaItem>,
        /** The queue-row index each item came from (same size as [items]). */
        val sourcePositions: List<Int>,
        /** Count of local rows whose window no longer resolves (B1). */
        val missingLocal: Int,
    )

    /**
     * Assembles [queueRows] into MediaItems. Local rows expand via windows;
     * stream rows become `pianostream://stream/<videoId>` items.
     */
    suspend fun assemble(queueRows: List<QueueItem>): AssembledQueue {
        val sorted = queueRows.sortedBy { it.position }
        val localKeys = sorted
            .mapNotNull { (PianoStreamUri.parse(it.mediaId) as? PianoStreamUri.Id.Local)?.contentKey }
        val windows = if (localKeys.isEmpty()) {
            com.pianostream.core.local.api.LocalWindowsResult(emptyList(), 0)
        } else {
            windowSource.windows(localKeys)
        }
        val windowByKey = windows.windows.associateBy { it.contentKey }
        var missing = windows.missingCount

        val items = ArrayList<MediaItem>(sorted.size)
        val positions = ArrayList<Int>(sorted.size)
        for (row in sorted) {
            val parsed = PianoStreamUri.parse(row.mediaId) ?: continue
            when (parsed) {
                is PianoStreamUri.Id.Stream -> {
                    items += streamItem(row, parsed.videoId)
                    positions += row.position
                }
                is PianoStreamUri.Id.Local -> {
                    val w = windowByKey[parsed.contentKey]
                    if (w == null) {
                        missing++
                        continue // B1 tombstone — row kept in db, item omitted
                    }
                    val art = artworkOf(parsed.contentKey)
                    items += localItems.build(
                        window = w,
                        discId = art?.discId ?: parsed.contentKey,
                        artworkDocumentUri = art?.artworkUri,
                        flacDurationMs = art?.flacDurationMs,
                        title = row.title,
                        artist = row.artist,
                        durationMs = row.durationMs,
                    )
                    positions += row.position
                }
            }
        }
        return AssembledQueue(items, positions, missingLocal = missing)
    }

    private fun streamItem(row: QueueItem, videoId: String): MediaItem {
        val extras: Bundle = bundleOf(
            LocalMediaItemFactory.EXTRA_CONTENT_KEY to videoId,
        )
        return MediaItem.Builder()
            .setMediaId(row.mediaId)
            .setUri(row.mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(row.title)
                    .setArtist(row.artist)
                    // Remote rows keep the allow-listed artwork URI (F9).
                    .setArtworkUri(row.artworkUri?.let(android.net.Uri::parse))
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }
}
