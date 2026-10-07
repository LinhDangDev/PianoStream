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

    private fun scannerFor(b: TreeBuilder, failAt: String? = null): LibraryScanner {
        val src = FakeSource(b.children, b.files).apply { this.failAt = failAt }
        return LibraryScanner(src) { uri -> b.files[uri]?.let { ByteArrayInputStream(it) } }
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
}
