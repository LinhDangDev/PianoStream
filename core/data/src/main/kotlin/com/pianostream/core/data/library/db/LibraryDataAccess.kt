package com.pianostream.core.data.library.db

import androidx.room.withTransaction
import com.pianostream.core.data.library.db.entity.CueSheetRawEntity
import com.pianostream.core.data.library.db.entity.DiscEntity
import com.pianostream.core.data.library.db.entity.TrackEntity
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.uri.PianoStreamUri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-only seam over `library.db` (+ the playback half needed for the B1
 * reconcile). `core:local` cannot see Room through its frozen dep set, so
 * every write/query the scanner needs funnels through here — no Room type
 * escapes the signatures.
 */
@Singleton
class LibraryDataAccess @Inject constructor(
    private val db: LibraryDatabase,
    private val playbackDb: PlaybackDatabase,
) {

    data class StoredDisc(
        val id: Long,
        val docId: String,
        val folderPath: String,
        val flacUri: String,
        val flacSizeBytes: Long,
        val flacLastModified: Long?,
        val scanComplete: Boolean,
        val artworkUri: String?,
        val flacDurationMs: Long?,
    )

    data class StoredTrack(
        val trackNo: Int,
        val startMs: Long,
        val endMs: Long?,
        val gainDb: Double?,
        val peak: Double?,
    )

    data class DiscWrite(
        val docId: String,
        val treeUri: String,
        val folderPath: String,
        val title: String,
        val performer: String?,
        val composer: String?,
        val genre: String?,
        val date: String?,
        val discId: String?,
        val discNumber: Int?,
        val totalDiscs: Int?,
        val cueUri: String?,
        val flacUri: String,
        val logUri: String?,
        val artworkUri: String?,
        val albumGainDb: Double?,
        val albumPeak: Double?,
        val flacDurationMs: Long?,
        val flacSizeBytes: Long,
        val flacLastModified: Long?,
    )

    data class TrackWrite(
        val indexInCue: Int,
        val trackNo: Int,
        val title: String,
        val performer: String?,
        val composer: String?,
        val startMs: Long,
        val endMs: Long?,
        val pregapStartMs: Long?,
        val gainDb: Double?,
        val peak: Double?,
        val isrc: String?,
        val flags: Int,
    )

    suspend fun allDiscs(): List<StoredDisc> =
        db.discDao().all().map {
            StoredDisc(
                it.id, it.docId, it.folderPath, it.flacUri, it.flacSizeBytes,
                it.flacLastModified, it.scanComplete, it.artworkUri,
                it.flacDurationMs,
            )
        }

    suspend fun tracksOrdered(discId: Long): List<StoredTrack> =
        db.trackDao().byDisc(discId).map {
            StoredTrack(it.trackNo, it.startMs, it.endMs, it.gainDb, it.peak)
        }

    /** One `@Transaction` per disc (M2): upsert + track rewrite + hash row. */
    suspend fun writeDisc(
        existingId: Long?,
        disc: DiscWrite,
        tracks: List<TrackWrite>,
        cueUri: String,
        cueContentHash: String,
        parserVersion: Int,
    ): Long = db.withTransaction {
        val discId = db.discDao().upsert(
            DiscEntity(
                id = existingId ?: 0,
                docId = disc.docId,
                treeUri = disc.treeUri,
                folderPath = disc.folderPath,
                title = disc.title,
                performer = disc.performer,
                composer = disc.composer,
                genre = disc.genre,
                date = disc.date,
                discId = disc.discId,
                discNumber = disc.discNumber,
                totalDiscs = disc.totalDiscs,
                cueUri = disc.cueUri,
                flacUri = disc.flacUri,
                logUri = disc.logUri,
                artworkUri = disc.artworkUri,
                albumGainDb = disc.albumGainDb,
                albumPeak = disc.albumPeak,
                flacDurationMs = disc.flacDurationMs,
                flacSizeBytes = disc.flacSizeBytes,
                flacLastModified = disc.flacLastModified,
                scannedAt = System.currentTimeMillis(),
                scanComplete = true,
            ),
        )
        db.trackDao().deleteByDisc(discId)
        db.trackDao().upsertAll(
            tracks.sortedBy { it.indexInCue }.map { t ->
                TrackEntity(
                    discId = discId, trackNo = t.trackNo, title = t.title,
                    performer = t.performer, composer = t.composer,
                    startMs = t.startMs, endMs = t.endMs,
                    pregapStartMs = t.pregapStartMs, gainDb = t.gainDb,
                    peak = t.peak, isrc = t.isrc, flags = t.flags,
                )
            },
        )
        db.cueSheetRawDao().upsert(
            CueSheetRawEntity(
                discId = discId, uri = cueUri,
                contentHash = cueContentHash, parserVersion = parserVersion,
                parsedAt = System.currentTimeMillis(),
            ),
        )
        discId
    }

    suspend fun deleteDiscsNotIn(docIds: Set<String>) {
        for (d in db.discDao().all()) {
            if (d.docId !in docIds) db.discDao().deleteById(d.id)
        }
    }

    /** Every local-scheme mediaId held by playback.db (B1). */
    suspend fun playbackLocalMediaIds(): Set<String> {
        val out = HashSet<String>()
        playbackDb.queueDao().queueSnapshot().mapTo(out) { it.mediaId }
        val sqlite = playbackDb.openHelper.writableDatabase
        for (table in listOf("play_history", "favorites")) {
            sqlite.query("SELECT mediaId FROM $table").use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
        }
        return out.filterTo(HashSet()) { PianoStreamUri.isLocal(it) }
    }

    /** B1 reconcile across queue/history/favorites; returns tombstoned count. */
    suspend fun reconcilePlayback(liveKeys: Set<String>): Int {
        val missing = playbackLocalMediaIds().count {
            it.substringAfterLast('/') !in liveKeys
        }
        playbackDb.maintenanceDao().reconcile(
            liveKeys.map { PianoStreamUri.local(it) },
        )
        return missing
    }
}
