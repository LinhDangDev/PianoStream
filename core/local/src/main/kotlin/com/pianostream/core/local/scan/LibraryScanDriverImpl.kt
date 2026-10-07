package com.pianostream.core.local.scan

import android.content.Context
import android.net.Uri
import com.pianostream.core.data.library.db.LibraryDataAccess
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.scan.LibraryScanDriver
import com.pianostream.core.local.saf.ContentResolverSafSource
import dagger.hilt.android.qualifiers.ApplicationContext
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
        ) { u -> resolver.openInputStream(Uri.parse(u)) }

        val previous = store.allDiscs().associateBy { it.docId }
        val previousCount = previous.size

        val scan = try {
            scanner.scan(Uri.parse(treeUri))
        } catch (e: SecurityException) {
            return ScanResult(0, 0, 0, 0, grantLost = true)
        }
        if (scan.discs.isEmpty() && previousCount > 0) {
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
        onProgress(ScanProgress(scanned, null))

        // M2 — never delete on a shrinking scan: stale discs stay until the
        // user confirms (LibraryViewModel's confirm path wipes + rescans).
        if (scan.discs.size >= previousCount) {
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

        if (scan.discs.size < previousCount) {
            warnings += "expected $previousCount discs, found ${scan.discs.size} " +
                "— stale rows kept pending confirmation"
        }
        if (reParsed > 0) {
            warnings += "$reParsed new/changed files since last scan"
        }
        return ScanResult(
            discCount = scan.discs.size,
            trackCount = trackTotal,
            durationMs = durationTotal,
            missingCount = missingCount,
            warnings = warnings,
        )
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
        ): Boolean =
            prior.scanComplete &&
                prior.flacSizeBytes == d.flac.size &&
                prior.flacLastModified == d.flac.lastModified &&
                prior.artworkUri == d.artworkUri
    }
}
