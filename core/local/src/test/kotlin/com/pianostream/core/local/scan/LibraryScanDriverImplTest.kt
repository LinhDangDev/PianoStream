package com.pianostream.core.local.scan

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.db.LibraryDataAccess
import com.pianostream.core.local.cue.CueSheet
import com.pianostream.core.local.saf.SafDocument
import com.pianostream.core.local.saf.SafDocumentKind
import org.junit.Test

/**
 * Incremental-scan fingerprint: the M2 skip-write predicate must see a
 * sibling `Front.jpeg` copied into a disc folder *after* the first scan —
 * the "0 new" artwork regression where the stored `artworkUri` stayed
 * null because only flac size+mtime were fingerprinted.
 */
class LibraryScanDriverImplTest {

    private fun doc(name: String, kind: SafDocumentKind, size: Long = 10, mtime: Long = 1000) =
        SafDocument(
            docId = "id:$name", uri = "content://x/$name", displayName = name,
            mimeType = "", size = size, lastModified = mtime, kind = kind,
        )

    private fun stored(
        artworkUri: String?,
        scanComplete: Boolean = true,
        flacSize: Long = 100,
        flacModified: Long = 1000,
    ) = LibraryDataAccess.StoredDisc(
        id = 1, docId = "d1", folderPath = "CD1",
        flacUri = "content://x/cd.flac", flacSizeBytes = flacSize,
        flacLastModified = flacModified, scanComplete = scanComplete,
        artworkUri = artworkUri, flacDurationMs = 60_000,
    )

    private fun scanned(
        artworkUri: String?,
        flacSize: Long = 100,
        flacModified: Long = 1000,
    ) = LibraryScanner.DiscScan(
        folderPath = "CD1", docId = "d1",
        cue = doc("cd.cue", SafDocumentKind.CUE),
        flac = doc("cd.flac", SafDocumentKind.FLAC, flacSize, flacModified),
        log = null,
        artworkUri = artworkUri,
        sheet = CueSheet(fileName = "cd.flac", tracks = emptyList()),
        windows = CueSheet.WindowResult(emptyList(), emptyList(), 0),
        flacDurationMs = 60_000,
        cueContentHash = "h", parserVersion = 1, warnings = emptyList(),
    )

    @Test
    fun identical_fingerprint_skips_write() {
        assertThat(
            LibraryScanDriverImpl.unchangedSince(
                stored("content://x/Front.jpeg"), scanned("content://x/Front.jpeg"),
            ),
        ).isTrue()
        assertThat(
            LibraryScanDriverImpl.unchangedSince(stored(null), scanned(null)),
        ).isTrue()
    }

    @Test
    fun artwork_copied_after_scan_triggers_rewrite() {
        // The reported bug: Front.jpeg appears next to an unchanged flac —
        // the disc must re-write so artworkUri reaches library.db.
        assertThat(
            LibraryScanDriverImpl.unchangedSince(
                stored(null), scanned("content://x/Front.jpeg"),
            ),
        ).isFalse()
    }

    @Test
    fun artwork_removed_or_replaced_triggers_rewrite() {
        assertThat(
            LibraryScanDriverImpl.unchangedSince(
                stored("content://x/Front.jpeg"), scanned(null),
            ),
        ).isFalse()
        // Replace-by-new-document changes the SAF document URI.
        assertThat(
            LibraryScanDriverImpl.unchangedSince(
                stored("content://x/old"), scanned("content://x/new"),
            ),
        ).isFalse()
    }

    @Test
    fun flac_change_still_triggers_rewrite() {
        assertThat(
            LibraryScanDriverImpl.unchangedSince(stored(null), scanned(null, flacSize = 200)),
        ).isFalse()
        assertThat(
            LibraryScanDriverImpl.unchangedSince(stored(null), scanned(null, flacModified = 2000)),
        ).isFalse()
    }

    @Test
    fun incomplete_scan_always_reparses() {
        assertThat(
            LibraryScanDriverImpl.unchangedSince(
                stored(null, scanComplete = false), scanned(null),
            ),
        ).isFalse()
    }
}
