package com.pianostream.core.local.scan

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.local.saf.SafDocument
import com.pianostream.core.local.saf.SafDocumentKind
import com.pianostream.core.local.saf.SafDocumentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * End-to-end scanner test over an in-memory SAF tree — covers the
 * depth-1/depth-2 disc layout, the F20 read guards, FILE-sibling rejection,
 * STREAMINFO-less FLAC skip and `SecurityException` abort (M2). SAF document
 * reads go through the `openRead` seam; no Room involved.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {

    private fun doc(
        name: String, kind: SafDocumentKind, size: Long = 10,
        docId: String = name,
    ) = SafDocument(
        docId = docId,
        uri = "content://tree/document/$docId",
        displayName = name, mimeType = "",
        size = size, lastModified = 1000, kind = kind,
    )

    private fun flacBytes(durationSamples: Long = 44100, rate: Int = 44100): ByteArray {
        // fLaC + STREAMINFO block
        val si = ByteArray(34)
        val packed = (rate.toLong() shl 44) or (2L shl 41) or (16L shl 36) or durationSamples
        for (i in 0..7) si[10 + i] = ((packed ushr (56 - 8 * i)) and 0xff).toByte()
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + si
    }

    private fun cueBytes(text: String) = text.toByteArray()

    private class FakeSource(
        private val children: Map<String, List<SafDocument>>,
        private val files: Map<String, ByteArray>,
    ) : SafDocumentSource {
        var failAt: String? = null
        override suspend fun children(
            treeUri: Uri, parentDocId: String,
        ): List<SafDocument> {
            if (parentDocId == failAt) throw SecurityException("grant revoked")
            return children[parentDocId] ?: emptyList()
        }
    }

    private class TreeBuilder {
        val children = linkedMapOf<String, MutableList<SafDocument>>()
        val files = linkedMapOf<String, ByteArray>()

        fun folder(docId: String, name: String, parent: String, vararg kids: SafDocument) {
            val dir = SafDocument(
                docId = docId, uri = "content://tree/document/$docId",
                displayName = name, mimeType = "vnd.android.document/directory",
                size = 0, lastModified = 0, kind = SafDocumentKind.FOLDER,
            )
            children.getOrPut(parent) { mutableListOf() } += dir
            children.getOrPut(docId) { mutableListOf() }.addAll(kids)
        }

        fun file(
            docId: String, name: String, parent: String,
            kind: SafDocumentKind, content: ByteArray = byteArrayOf(1),
            size: Long = content.size.toLong(),
        ) {
            val d = SafDocument(
                docId = docId, uri = "content://tree/document/$docId",
                displayName = name, mimeType = "", size = size,
                lastModified = 1000, kind = kind,
            )
            children.getOrPut(parent) { mutableListOf() } += d
            files[d.uri] = content
        }
    }

    private fun cue(name: String, file: String, tracks: List<Pair<Long, Long?>>): String =
        buildString {
            append("PERFORMER \"P\"\nTITLE \"$name\"\nFILE \"$file\" WAVE\n")
            tracks.forEachIndexed { i, (start, pre) ->
                append("  TRACK %02d AUDIO\n    TITLE \"t%d\"\n    SONGWRITER \"C%d\"\n".format(i + 1, i, i))
                if (pre != null) append("    INDEX 00 %s\n".format(msToCue(pre)))
                append("    INDEX 01 %s\n".format(msToCue(start)))
            }
        }

    private fun msToCue(ms: Long): String {
        val total = ms / 1000
        val ff = (ms % 1000) * 75 / 1000
        return "%02d:%02d:%02d".format(total / 60, total % 60, ff)
    }

    private val ROOT = "primary:Music"

    private fun scannerFor(
        b: TreeBuilder,
        failAt: String? = null,
        tagReader: AudioTagReader = AudioTagReader { null },
    ): LibraryScanner {
        val src = FakeSource(b.children, b.files).apply { this.failAt = failAt }
        return LibraryScanner(src, tagReader = tagReader) { uri ->
            b.files[uri]?.let { ByteArrayInputStream(it) }
        }
    }

    @Test
    fun depth1_and_depth2_discs_scanned() = runBlocking {
        val b = TreeBuilder()
        // disc at depth 1
        b.file("cue1", "cd1.cue", "d1", SafDocumentKind.CUE,
            cue("CD1", "cd1.flac", listOf(0L to null, 10_000L to 8_000L)).toByteArray())
        b.file("flac1", "cd1.flac", "d1", SafDocumentKind.FLAC, flacBytes(44100 * 60))
        b.file("f1", "Front.jpeg", "d1", SafDocumentKind.JPEG)
        b.folder("d1", "CD1", ROOT)
        // disc at depth 2
        b.file("cue2", "cd2.cue", "d2b", SafDocumentKind.CUE,
            cue("CD2", "cd2.flac", listOf(0L to null, 30_000L to null)).toByteArray())
        b.file("flac2", "cd2.flac", "d2b", SafDocumentKind.FLAC, flacBytes(44100 * 40))
        b.folder("d2b", "CD002 - Artist", "d2")
        b.folder("d2", "CD001-4 - Set", ROOT)
        // depth-3 score tree produces no audio rows
        b.file("pdf1", "score.pdf", "scores", SafDocumentKind.PRUNED)
        b.folder("scores", "DVD - Piano Scores", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).hasSize(2)
        val d1 = result.discs.single { it.folderPath == "CD1" }
        assertThat(d1.windows.windows).hasSize(2)
        assertThat(d1.windows.windows[1].startMs).isEqualTo(10_000)
        assertThat(d1.windows.windows[1].pregapStartMs).isEqualTo(8_000)
        assertThat(d1.artworkUri).isEqualTo("content://tree/document/f1")
        val d2 = result.discs.single { it.folderPath.endsWith("CD002 - Artist") }
        assertThat(d2.windows.windows).hasSize(2)
        assertThat(result.jpegCount).isEqualTo(1)
    }

    @Test
    fun oversize_cue_never_read_and_reported() = runBlocking {
        val b = TreeBuilder()
        b.file("cueO", "big.cue", "d1", SafDocumentKind.CUE,
            byteArrayOf(1), size = 2L * 1024 * 1024)
        b.file("flacO", "big.flac", "d1", SafDocumentKind.FLAC, flacBytes())
        b.folder("d1", "BIG", ROOT)
        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).isEmpty()
        assertThat(result.warnings.any { it.contains("oversize") }).isTrue()
    }

    @Test
    fun file_escape_target_not_matched_to_sibling() = runBlocking {
        val b = TreeBuilder()
        b.file("cueE", "e.cue", "d1", SafDocumentKind.CUE,
            cue("E", "../secret.flac", listOf(0L to null)).toByteArray())
        b.file("flacE", "e.flac", "d1", SafDocumentKind.FLAC, flacBytes())
        b.folder("d1", "E", ROOT)
        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs.single().windows.windows).isEmpty()
        assertThat(result.warnings.any { it.contains("escapes") }).isTrue()
    }

    @Test
    fun streaminfo_less_flac_skips_last_track() = runBlocking {
        val b = TreeBuilder()
        b.file("cueN", "n.cue", "d1", SafDocumentKind.CUE,
            cue("N", "n.flac", listOf(0L to null, 10_000L to null)).toByteArray())
        b.file("flacN", "n.flac", "d1", SafDocumentKind.FLAC, byteArrayOf(0, 1, 2))
        b.folder("d1", "N", ROOT)
        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        val d = result.discs.single()
        // last track has no end bound → skipped + reported (M3)
        assertThat(d.windows.windows).hasSize(1)
        assertThat(d.windows.windows[0].endMs).isEqualTo(10_000)
        assertThat(d.windows.skipped.any { it.contains("no end bound") }).isTrue()
    }

    @Test
    fun security_exception_aborts_scan(): Unit {
        val b = TreeBuilder()
        b.file("cueX", "x.cue", "d1", SafDocumentKind.CUE, cue("X", "x.flac", listOf(0L to null)).toByteArray())
        b.file("flacX", "x.flac", "d1", SafDocumentKind.FLAC, flacBytes())
        b.folder("d1", "X", ROOT)
        val s = scannerFor(b, failAt = "d1")
        assertThrows(SecurityException::class.java) {
            runBlocking { s.scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")) }
        }
    }

    @Test
    fun cue_inside_root_itself_scans() = runBlocking {
        val b = TreeBuilder()
        b.file("cueR", "root.cue", ROOT, SafDocumentKind.CUE,
            cue("R", "root.flac", listOf(0L to null)).toByteArray())
        b.file("flacR", "root.flac", ROOT, SafDocumentKind.FLAC, flacBytes())
        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).hasSize(1)
        assertThat(result.discs.single().windows.windows).hasSize(1)
    }

    @Test
    fun loose_audio_in_plain_folder_becomes_single() = runBlocking {
        val b = TreeBuilder()
        b.file("mp3A", "song one.mp3", "mix", SafDocumentKind.AUDIO)
        b.file("mp3B", "two.opus", "mix", SafDocumentKind.AUDIO)
        b.folder("mix", "Mix", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).isEmpty()
        assertThat(result.singles).hasSize(2)
        val one = result.singles.single { it.docId == "mp3A" }
        // pseudo disc path + fallbacks: title = stem, album = parent folder
        assertThat(one.folderPath).isEqualTo("Mix/song one.mp3")
        assertThat(one.title).isEqualTo("song one")
        assertThat(one.album).isEqualTo("Mix")
        assertThat(one.audio.uri).isEqualTo("content://tree/document/mp3A")
        assertThat(one.artworkUri).isNull()
    }

    @Test
    fun loose_audio_at_picked_root_scanned() = runBlocking {
        val b = TreeBuilder()
        b.file("mp3R", "root song.mp3", ROOT, SafDocumentKind.AUDIO)
        b.folder("empty", "Empty", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.singles).hasSize(1)
        assertThat(result.singles.single().folderPath).isEqualTo("root song.mp3")
    }

    @Test
    fun loose_flac_in_plain_folder_is_single() = runBlocking {
        // .flac keeps its FLAC kind through classify — still a single when no
        // cue references it (contract: flac in a folder with no cue).
        val b = TreeBuilder()
        b.file("fA", "loose.flac", "d", SafDocumentKind.FLAC, flacBytes(44100 * 5))
        b.folder("d", "Loose", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.singles).hasSize(1)
        // STREAMINFO duration wins without a tag reader.
        assertThat(result.singles.single().durationMs).isEqualTo(5_000)
    }

    @Test
    fun orphan_audio_beside_cue_becomes_single() = runBlocking {
        val b = TreeBuilder()
        b.file("cueO", "cd.cue", "d", SafDocumentKind.CUE,
            cue("CD", "cd.flac", listOf(0L to null)).toByteArray())
        b.file("flacO", "cd.flac", "d", SafDocumentKind.FLAC, flacBytes())
        b.file("flacOrphan", "bonus.flac", "d", SafDocumentKind.FLAC, flacBytes(44100 * 7))
        b.file("mp3Orphan", "bonus.mp3", "d", SafDocumentKind.AUDIO)
        b.folder("d", "CD", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).hasSize(1)
        // the cue's FILE target is NOT a single; the other two audio docs are
        assertThat(result.singles.map { it.docId })
            .containsExactly("flacOrphan", "mp3Orphan")
        assertThat(result.singles.map { it.audio.kind }.toSet())
            .isEqualTo(setOf(SafDocumentKind.FLAC, SafDocumentKind.AUDIO))
    }

    @Test
    fun cue_file_target_of_any_audio_kind_is_not_single() = runBlocking {
        // wav FILE target binds to the disc (AUDIO kind accepted) — not a single.
        val b = TreeBuilder()
        b.file("cueW", "cd.cue", "d", SafDocumentKind.CUE,
            cue("CD", "cd.wav", listOf(0L to null)).toByteArray())
        b.file("wavW", "cd.wav", "d", SafDocumentKind.AUDIO)
        b.folder("d", "CD", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.singles).isEmpty()
        assertThat(result.discs.single().flac.uri).isEqualTo("content://tree/document/wavW")
    }

    @Test
    fun second_cue_file_target_is_not_single() = runBlocking {
        // a folder with two cues: both FILE targets stay out of the singles pool.
        val b = TreeBuilder()
        b.file("cueA", "a.cue", "d", SafDocumentKind.CUE,
            cue("A", "a.flac", listOf(0L to null)).toByteArray())
        b.file("cueB", "b.cue", "d", SafDocumentKind.CUE,
            cue("B", "b.flac", listOf(0L to null)).toByteArray())
        b.file("flacA", "a.flac", "d", SafDocumentKind.FLAC, flacBytes())
        b.file("flacB", "b.flac", "d", SafDocumentKind.FLAC, flacBytes())
        b.file("flacC", "c.flac", "d", SafDocumentKind.FLAC, flacBytes())
        b.folder("d", "CD", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).hasSize(1) // one disc per folder (first cue)
        assertThat(result.singles.map { it.docId }).containsExactly("flacC")
        assertThat(result.singles.single().folderPath).isEqualTo("CD/c.flac")
    }

    @Test
    fun single_tags_map_through() = runBlocking {
        val b = TreeBuilder()
        b.file("mp3T", "file.mp3", "d", SafDocumentKind.AUDIO)
        b.folder("d", "Fold", ROOT)
        val reader = AudioTagReader { uri ->
            if (uri.endsWith("mp3T")) {
                AudioTags(
                    title = "Real Title", artist = "Real Artist",
                    album = "Real Album", genre = "Jazz", date = "2020",
                    durationMs = 123_000, embeddedArt = byteArrayOf(9, 9),
                )
            } else {
                null
            }
        }
        val s = scannerFor(b, tagReader = reader).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        val one = s.singles.single()
        assertThat(one.title).isEqualTo("Real Title")
        assertThat(one.performer).isEqualTo("Real Artist")
        assertThat(one.album).isEqualTo("Real Album")
        assertThat(one.genre).isEqualTo("Jazz")
        assertThat(one.date).isEqualTo("2020")
        assertThat(one.durationMs).isEqualTo(123_000)
        assertThat(one.embeddedArt).isEqualTo(byteArrayOf(9, 9))
    }

    @Test
    fun single_folder_jpeg_is_artwork() = runBlocking {
        val b = TreeBuilder()
        b.file("mp3W", "s.mp3", "d", SafDocumentKind.AUDIO)
        b.file("artW", "Front.jpeg", "d", SafDocumentKind.JPEG)
        b.folder("d", "D", ROOT)
        val s = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(s.singles.single().artworkUri).isEqualTo("content://tree/document/artW")
    }

    @Test
    fun singles_do_not_descend_past_depth_or_into_disc_subtrees() = runBlocking {
        val b = TreeBuilder()
        // audio inside a cue folder's SUBfolder is unreachable (no descent) —
        // covered by construction: build a disc folder with a nested dir that
        // also holds an mp3; the scanner never lists it.
        b.file("cueX", "x.cue", "d", SafDocumentKind.CUE,
            cue("X", "x.flac", listOf(0L to null)).toByteArray())
        b.file("flacX", "x.flac", "d", SafDocumentKind.FLAC, flacBytes())
        val inner = SafDocument(
            docId = "inner", uri = "content://tree/document/inner",
            displayName = "inner", mimeType = "vnd.android.document/directory",
            size = 0, lastModified = 0, kind = SafDocumentKind.FOLDER,
        )
        b.children.getOrPut("d") { mutableListOf() }.add(inner)
        b.file("mp3Deep", "deep.mp3", "inner", SafDocumentKind.AUDIO)
        b.folder("d", "CD", ROOT)

        val result = scannerFor(b).scan(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertThat(result.discs).hasSize(1)
        assertThat(result.singles).isEmpty() // no descent into disc folders
    }
}
