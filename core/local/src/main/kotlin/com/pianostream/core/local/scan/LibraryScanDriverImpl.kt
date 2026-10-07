package com.pianostream.core.local.scan

import android.content.Context
import android.net.Uri
import com.pianostream.core.data.library.db.LibraryDataAccess
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.scan.LibraryScanDriver
import com.pianostream.core.local.saf.ContentResolverSafSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scan → `library.db` persistence + post-scan B1 reconcile (Phase-3-owned;
 * lives in `core:local` because it needs SAF + cue parsing — every write
 * goes through the Room seam `LibraryDataAccess` since this module's frozen
 * dep set cannot see Room).
 *
 * Durability rules (M2/E2): per-disc `@Transaction` with a `scanComplete`
 * marker — an incomplete disc always re-parses; the first `SecurityException`
 * aborts keeping previous rows (`grantLost`); `0 discs && previous > 0`
 * returns an error result, never an empty success; a disc is skipped only
 * when `flacLastModified`+`flacSizeBytes` AND the resolved `artworkUri`
 * match AND `scanComplete` is set — the artwork term is what makes a
 * `Front.jpeg` dropped beside the cue/flac after the first scan
 * re-scannable.
 *
 * Loose-file singles persist as one disc + one track row each with
 * `cueUri = null` (the single marker — no schema change). Embedded artwork
 * is written to the shared `filesDir/artwork/<discKey>.jpg` slot that
 * `LocalMediaItemFactory` reads, so singles get lockscreen art without a
 * `content://` artwork document. The same fingerprint governs rescan, so a
 * deleted file's disc row drops out through `deleteDiscsNotIn` like any disc.
 */
@Singleton
class LibraryScanDriverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LibraryDataAccess,
) : LibraryScanDriver {

    override suspend fun run(
        treeUri: String,
        onProgress: suspend (ScanProgress) -> Unit,
    ): ScanResult {
        val resolver = context.contentResolver
        val scanner = LibraryScanner(
            source = ContentResolverSafSource(resolver),
            tagReader = MetadataRetrieverTagReader(resolver),
        ) { u -> resolver.openInputStream(Uri.parse(u)) }

        val previous = store.allDiscs().associateBy { it.docId }
        val previousCount = previous.size

        val scan = try {
            scanner.scan(Uri.parse(treeUri))
        } catch (e: SecurityException) {
            return ScanResult(0, 0, 0, 0, grantLost = true)
        }
        val foundCount = scan.discs.size + scan.singles.size
        if (foundCount == 0 && previousCount > 0) {
            return ScanResult(
                0, 0, 0, 0,
                warnings = listOf(
                    "0 discs found where $previousCount before — refusing to wipe; " +
                        "re-pick the library folder or confirm a clear",
                ),
            )
        }

        val warnings = scan.warnings.toMutableList()
        val foundDocIds = scan.discs.mapTo(HashSet()) { it.docId }
        scan.singles.mapTo(foundDocIds) { it.docId }
        var scanned = 0
        var trackTotal = 0
        var durationTotal = 0L
        var reParsed = 0

        for (d in scan.discs) {
            val prior = previous[d.docId]
            val unchanged = prior != null && unchangedSince(prior, d)
            if (!unchanged) {
                reParsed++
                store.writeDisc(
                    existingId = prior?.id,
                    disc = LibraryDataAccess.DiscWrite(
                        docId = d.docId,
                        treeUri = treeUri,
                        folderPath = d.folderPath,
                        title = d.sheet.title ?: d.folderPath.substringAfterLast('/'),
                        performer = d.sheet.performer,
                        composer = d.sheet.tracks.mapNotNull { it.songwriter }
                            .distinct().joinToString("; ").ifEmpty { null },
                        genre = d.sheet.rem.genre,
                        date = d.sheet.rem.date,
                        discId = d.sheet.rem.discId,
                        discNumber = d.sheet.rem.discNumber,
                        totalDiscs = d.sheet.rem.totalDiscs,
                        cueUri = d.cue.uri,
                        flacUri = d.flac.uri,
                        logUri = d.log?.uri,
                        artworkUri = d.artworkUri,
                        albumGainDb = d.sheet.rem.albumGainDb,
                        albumPeak = d.sheet.rem.albumPeak,
                        flacDurationMs = d.flacDurationMs,
                        flacSizeBytes = d.flac.size,
                        flacLastModified = d.flac.lastModified,
                    ),
                    tracks = d.windows.windows.map { w ->
                        val t = d.sheet.tracks[w.indexInCue]
                        LibraryDataAccess.TrackWrite(
                            indexInCue = w.indexInCue, trackNo = t.trackNo,
                            title = t.title ?: "Track ${t.trackNo}",
                            performer = t.performer,
                            composer = t.songwriter,
                            startMs = w.startMs, endMs = w.endMs,
                            pregapStartMs = w.pregapStartMs,
                            gainDb = t.trackGainDb, peak = t.trackPeak,
                            isrc = t.isrc, flags = t.flags,
                        )
                    },
                    cueUri = d.cue.uri,
                    cueContentHash = d.cueContentHash,
                    parserVersion = d.parserVersion,
                )
            }
            scanned++
            trackTotal += d.windows.windows.size
            durationTotal += d.flacDurationMs ?: 0L
            if (scanned % ScanPlan.REPORT_EVERY == 0) {
                onProgress(ScanProgress(scanned, d.folderPath))
            }
        }

        for (s in scan.singles) {
            val prior = previous[s.docId]
            if (prior == null || !unchangedSince(prior, s)) {
                reParsed++
                store.writeSingleDisc(
                    existingId = prior?.id,
                    disc = LibraryDataAccess.DiscWrite(
                        docId = s.docId,
                        treeUri = treeUri,
                        folderPath = s.folderPath,
                        title = s.album ?: s.title,
                        performer = s.performer,
                        composer = null,
                        genre = s.genre,
                        date = s.date,
                        discId = null,
                        discNumber = null,
                        totalDiscs = null,
                        // null cueUri = the single marker — never a real cue.
                        cueUri = null,
                        flacUri = s.audio.uri,
                        logUri = null,
                        artworkUri = s.artworkUri,
                        albumGainDb = null,
                        albumPeak = null,
                        flacDurationMs = s.durationMs,
                        flacSizeBytes = s.audio.size,
                        flacLastModified = s.audio.lastModified,
                    ),
                    track = LibraryDataAccess.TrackWrite(
                        indexInCue = 0,
                        trackNo = 1,
                        title = s.title,
                        performer = s.performer,
                        composer = null,
                        startMs = 0,
                        endMs = s.durationMs,
                        pregapStartMs = null,
                        gainDb = null,
                        peak = null,
                        isrc = null,
                        flags = 0,
                    ),
                )
            }
            cacheEmbeddedArtwork(s)
            scanned++
            trackTotal += 1
            durationTotal += s.durationMs ?: 0L
            if (scanned % ScanPlan.REPORT_EVERY == 0) {
                onProgress(ScanProgress(scanned, s.folderPath))
            }
        }
        onProgress(ScanProgress(scanned, null))

        // M2 — never delete on a shrinking scan: stale discs stay until the
        // user confirms (LibraryViewModel's confirm path wipes + rescans).
        if (foundCount >= previousCount) {
            store.deleteDiscsNotIn(foundDocIds)
        }

        // B1 reconcile: recompute live keys, tombstone dangling playback rows.
        val liveKeys = HashSet<String>()
        for (disc in store.allDiscs()) {
            store.tracksOrdered(disc.id).forEachIndexed { idx, _ ->
                liveKeys += LibraryHasher.contentKey(disc.folderPath, idx)
            }
        }
        val missingCount = store.reconcilePlayback(liveKeys)

        if (foundCount < previousCount) {
            warnings += "expected $previousCount discs, found $foundCount " +
                "— stale rows kept pending confirmation"
        }
        if (reParsed > 0) {
            warnings += "$reParsed new/changed files since last scan"
        }
        if (scan.singles.isNotEmpty()) {
            warnings += "${scan.singles.size} loose files indexed as singles"
        }
        return ScanResult(
            discCount = foundCount,
            trackCount = trackTotal,
            durationMs = durationTotal,
            missingCount = missingCount,
            warnings = warnings,
        )
    }

    /**
     * Embedded-art write into the shared `filesDir/artwork/<discKey>.jpg`
     * slot that `LocalMediaItemFactory.artworkBytesFor` reads — singles have
     * no artwork document to stamp, so the cache is their only art source.
     * Skipped when a folder JPEG exists: the fetch path then fills the slot
     * with the resolved artwork instead (artworkUri wins over embedded art).
     */
    private fun cacheEmbeddedArtwork(s: LibraryScanner.SingleScan) {
        val art = s.embeddedArt ?: return
        if (s.artworkUri != null) return
        runCatching {
            val dir = File(context.filesDir, "artwork")
            dir.mkdirs()
            File(dir, "${LibraryHasher.discKey(s.folderPath)}.jpg").writeBytes(art)
        }
    }

    companion object {
        /**
         * Incremental-scan fingerprint: a disc row is rewritten when the
         * flac identity, the resolved artwork identity, or the M2
         * `scanComplete` marker disagrees. Artwork compares by document URI
         * (add, remove, or replace-by-new-document all change it; an
         * in-place overwrite under the same docId is not detectable without
         * a stored artwork timestamp).
         */
        internal fun unchangedSince(
            prior: LibraryDataAccess.StoredDisc,
            d: LibraryScanner.DiscScan,
        ): Boolean = unchanged(prior, d.flac.size, d.flac.lastModified, d.artworkUri)

        /** Same fingerprint for loose-file singles (audio doc identity). */
        internal fun unchangedSince(
            prior: LibraryDataAccess.StoredDisc,
            s: LibraryScanner.SingleScan,
        ): Boolean = unchanged(prior, s.audio.size, s.audio.lastModified, s.artworkUri)

        private fun unchanged(
            prior: LibraryDataAccess.StoredDisc,
            flacSize: Long,
            flacLastModified: Long,
            artworkUri: String?,
        ): Boolean =
            prior.scanComplete &&
                prior.flacSizeBytes == flacSize &&
                prior.flacLastModified == flacLastModified &&
                prior.artworkUri == artworkUri
    }
}
