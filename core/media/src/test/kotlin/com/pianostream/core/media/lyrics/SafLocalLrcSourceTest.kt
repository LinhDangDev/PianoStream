package com.pianostream.core.media.lyrics

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.local.saf.SafDocument
import com.pianostream.core.local.saf.SafDocumentKind
import com.pianostream.core.local.saf.SafDocumentSource
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sibling-`.lrc` lookup: `file://`/bare-path scans the temp dir; the SAF
 * branch drives a fake [SafDocumentSource] + injected stream opener, so no
 * real documents provider is needed.
 */
@RunWith(RobolectricTestRunner::class)
class SafLocalLrcSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeSafSource(
        private val docs: List<SafDocument> = emptyList(),
    ) : SafDocumentSource {
        var lastTree: Uri? = null
        var lastParent: String? = null
        override suspend fun children(treeUri: Uri, parentDocId: String): List<SafDocument> {
            lastTree = treeUri; lastParent = parentDocId; return docs
        }
    }

    private fun source(
        saf: SafDocumentSource = FakeSafSource(),
        openInput: (Uri) -> java.io.InputStream? = { null },
    ) = SafLocalLrcSource(context, saf, openInput)

    @Test
    fun file_scheme_finds_sibling_any_case() = runTest {
        val dir = tmp.newFolder()
        java.io.File(dir, "song.flac").writeBytes(byteArrayOf(0))
        java.io.File(dir, "SONG.LRC").writeText("[00:01.00]x")
        assertThat(source().find("file://${dir}/song.flac")).isEqualTo("[00:01.00]x")
    }

    @Test
    fun bare_path_also_works() = runTest {
        val dir = tmp.newFolder()
        java.io.File(dir, "song.flac").writeBytes(byteArrayOf(0))
        java.io.File(dir, "song.lrc").writeText("[00:02.00]y")
        assertThat(source().find("$dir/song.flac")).isEqualTo("[00:02.00]y")
    }

    @Test
    fun no_sibling_returns_null() = runTest {
        val dir = tmp.newFolder()
        java.io.File(dir, "song.flac").writeBytes(byteArrayOf(0))
        assertThat(source().find("file://${dir}/song.flac")).isNull()
    }

    @Test
    fun non_tree_content_uri_returns_null() = runTest {
        assertThat(source().find("content://media/external/audio/123")).isNull()
    }

    @Test
    fun saf_tree_lists_parent_and_matches_name() = runTest {
        val doc = SafDocument(
            docId = "primary:Music/Album/song.lrc",
            uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fsong.lrc",
            displayName = "song.lrc",
            mimeType = "application/octet-stream",
            size = 12L, lastModified = 0L, kind = SafDocumentKind.OTHER,
        )
        val saf = FakeSafSource(listOf(doc))
        val src = source(saf) { ByteArrayInputStream("[00:03.00]z".toByteArray()) }
        val uri = "content://com.android.externalstorage.documents" +
            "/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fsong.flac"

        assertThat(src.find(uri)).isEqualTo("[00:03.00]z")
        assertThat(saf.lastParent).isEqualTo("primary:Music/Album")
        assertThat(saf.lastTree.toString())
            .isEqualTo("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    }

    @Test
    fun saf_tree_without_match_returns_null() = runTest {
        val saf = FakeSafSource(emptyList())
        val src = source(saf) { ByteArrayInputStream("x".toByteArray()) }
        val uri = "content://com.android.externalstorage.documents" +
            "/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fsong.flac"
        assertThat(src.find(uri)).isNull()
        assertThat(saf.lastParent).isEqualTo("primary:Music/Album")
    }
}
