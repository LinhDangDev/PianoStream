package com.pianostream.core.local.scan

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.db.LibraryDataAccess
import com.pianostream.core.local.api.LocalLibraryIndex
import org.junit.Test

/**
 * `windows()` contract + CC2 byte-identity: a `contentKey` is a pure hash of
 * `(folderPath, ordinal)` — rebuilding the index after a delete+rescan yields
 * the same `pianostream://local/<key>` mediaIds bit-for-bit, with the disc's
 * `content://` flacUri and exact gain/peak carried through.
 */
class LocalTrackWindowSourceTest {

    private fun disc(path: String) = LibraryDataAccess.StoredDisc(
        id = 1, docId = "d", folderPath = path,
        flacUri = "content://com.android.externalstorage.documents/document/flac",
        flacSizeBytes = 1, flacLastModified = 1, scanComplete = true,
        artworkUri = "content://art/Front.jpeg", flacDurationMs = 600_000,
    )

    private fun track(no: Int) = LibraryDataAccess.StoredTrack(
        trackNo = no, startMs = no * 10_000L, endMs = (no + 1) * 10_000L,
        gainDb = -7.83, peak = 0.995483,
    )

    @Test
    fun index_carries_flac_uri_and_exact_gains() {
        val index = LocalLibraryIndex.buildIndex(
            listOf(disc("CD1") to listOf(track(1), track(2))),
        )
        assertThat(index).hasSize(2)
        val e = index.values.first()
        assertThat(e.flacUri).startsWith("content://")
        assertThat(e.artworkUri).isEqualTo("content://art/Front.jpeg")
        assertThat(e.flacDurationMs).isEqualTo(600_000)
        assertThat(e.gainDb).isWithin(0.0001).of(-7.83)
        assertThat(e.peak).isWithin(0.0001).of(0.995483)
    }

    @Test
    fun content_keys_byte_identical_across_rebuilds() {
        // CC2: clear + rescan produces the same mediaIds — no rowids.
        val before = LocalLibraryIndex.buildIndex(
            listOf(disc("CD001 - Artist") to listOf(track(1), track(2))),
        ).keys.toList()
        val after = LocalLibraryIndex.buildIndex(
            listOf(disc("CD001 - Artist") to listOf(track(1), track(2))),
        ).keys.toList()
        assertThat(after).containsExactlyElementsIn(before).inOrder()
        assertThat(before.first()).matches("pianostream|[a-f0-9]{32}|[a-f0-9]{32}")
        assertThat(before.first()).hasLength(32)
    }

    @Test
    fun keys_differ_across_discs_and_ordinals() {
        val a = LibraryHasher.contentKey("CD1", 0)
        assertThat(a).isNotEqualTo(LibraryHasher.contentKey("CD1", 1))
        assertThat(a).isNotEqualTo(LibraryHasher.contentKey("CD2", 0))
        assertThat(LibraryHasher.discKey("CD1")).isNotEqualTo(a)
        assertThat(LibraryHasher.mediaId("CD1", 0))
            .isEqualTo("pianostream://local/$a")
    }

    @Test
    fun single_row_index_resolves_and_null_endms_is_valid() {
        // A "single" is a one-track disc at a file-shaped pseudo path; its
        // StoredTrack.endMs may be null (unknown tag duration) and still
        // produces a valid 0..EOF window (LocalTrackWindow allows null).
        val single = LibraryDataAccess.StoredTrack(
            trackNo = 1, startMs = 0, endMs = null, gainDb = null, peak = null,
        )
        val index = LocalLibraryIndex.buildIndex(
            listOf(disc("Mix/song.mp3") to listOf(single)),
        )
        assertThat(index).hasSize(1)
        val e = index.values.single()
        assertThat(e.contentKey).isEqualTo(LibraryHasher.contentKey("Mix/song.mp3", 0))
        assertThat(e.discKey).isEqualTo(LibraryHasher.discKey("Mix/song.mp3"))
        assertThat(e.startMs).isEqualTo(0)
        assertThat(e.endMs).isNull()

        val w = com.pianostream.core.local.api.LocalTrackWindow(
            contentKey = e.contentKey, flacUri = e.flacUri,
            startMs = e.startMs, endMs = e.endMs,
            gainDb = e.gainDb, peak = e.peak,
        )
        assertThat(w.endMs).isNull() // clipping 0..end — full file
    }

    @Test
    fun single_paths_do_not_collide_with_folder_paths() {
        // a single's pseudo path carries the filename, so it can't share a
        // contentKey with the disc folder it lives in.
        assertThat(LibraryHasher.contentKey("CD1", 0))
            .isNotEqualTo(LibraryHasher.contentKey("CD1/bonus.mp3", 0))
        assertThat(LibraryHasher.discKey("CD1"))
            .isNotEqualTo(LibraryHasher.discKey("CD1/bonus.mp3"))
    }
}
