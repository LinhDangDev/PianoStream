package com.pianostream.core.local.api

import com.pianostream.core.data.library.db.LibraryDataAccess
import com.pianostream.core.local.scan.LibraryHasher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `contentKey → library row` index shared by the window source and the
 * queue's artwork lookup: a `contentKey` is `LibraryHasher(folderPath,
 * ordinal)` where `ordinal` is the track's position among its disc's tracks
 * ordered by `trackNo` — equal to cue-file order for sequential `TRACK n`
 * sheets and stable across rescan (B3/CC2).
 */
@Singleton
class LocalLibraryIndex @Inject constructor(
    private val store: LibraryDataAccess,
) {

    data class Entry(
        val contentKey: String,
        val discKey: String,
        val flacUri: String,
        val artworkUri: String?,
        val flacDurationMs: Long?,
        val startMs: Long,
        val endMs: Long?,
        val gainDb: Double?,
        val peak: Double?,
    )

    suspend fun index(): Map<String, Entry> {
        val pairs = store.allDiscs().map { it to store.tracksOrdered(it.id) }
        return buildIndex(pairs)
    }

    companion object {

        internal fun buildIndex(
            pairs: List<
                Pair<LibraryDataAccess.StoredDisc, List<LibraryDataAccess.StoredTrack>>,
                >,
        ): Map<String, Entry> {
            val out = LinkedHashMap<String, Entry>()
            for ((disc, tracks) in pairs) {
                val discKey = LibraryHasher.discKey(disc.folderPath)
                tracks.forEachIndexed { idx, t ->
                    val key = LibraryHasher.contentKey(disc.folderPath, idx)
                    out[key] = Entry(
                        contentKey = key,
                        discKey = discKey,
                        flacUri = disc.flacUri,
                        artworkUri = disc.artworkUri,
                        flacDurationMs = disc.flacDurationMs,
                        startMs = t.startMs,
                        endMs = t.endMs,
                        gainDb = t.gainDb,
                        peak = t.peak,
                    )
                }
            }
            return out
        }
    }
}
