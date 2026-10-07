package com.pianostream.core.media.queue

import android.content.Context
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.local.api.LocalTrackWindow
import com.pianostream.core.local.api.LocalTrackWindowSource
import com.pianostream.core.local.api.LocalWindowsResult
import com.pianostream.core.media.local.LocalMediaItemFactory
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CC1 — the durable queue is assembled as one `MediaItem` per track;
 * B1 — dead local keys are omitted but counted, never silently dropped.
 */
@RunWith(RobolectricTestRunner::class)
class QueueAssemblerTest {

    private class FakeWindows(
        private val byKey: Map<String, LocalTrackWindow>,
        private val missing: Int = 0,
    ) : LocalTrackWindowSource {
        val calls = mutableListOf<List<String>>()
        override suspend fun windows(contentKeys: List<String>): LocalWindowsResult {
            calls += contentKeys
            return LocalWindowsResult(
                windows = contentKeys.mapNotNull { byKey[it] },
                missingCount = missing,
            )
        }
    }

    private lateinit var context: Context
    private lateinit var factory: LocalMediaItemFactory

    private fun row(
        position: Int,
        mediaId: String,
        source: MediaSource = if (mediaId.contains("/local/")) MediaSource.LOCAL else MediaSource.YOUTUBE,
        title: String = "T$position",
    ) = QueueItem(
        position = position,
        mediaId = mediaId,
        title = title,
        artist = "A",
        artworkUri = null,
        source = source,
        durationMs = 60_000,
        addedAt = 1,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = LocalMediaItemFactory(context)
    }

    @Test
    fun stream_rows_become_one_item_each() = runTest {
        val rows = listOf(
            row(0, "pianostream://stream/vid00000000"),
            row(1, "pianostream://stream/vid11111111"),
        )
        val asm = QueueAssembler(factory, FakeWindows(emptyMap())) { null }
            .assemble(rows)
        assertThat(asm.items).hasSize(2)
        assertThat(asm.sourcePositions).containsExactly(0, 1).inOrder()
        assertThat(asm.items[0].localConfiguration!!.uri.toString())
            .isEqualTo("pianostream://stream/vid00000000")
        assertThat(asm.missingLocal).isEqualTo(0)
    }

    @Test
    fun local_rows_expand_to_clipped_items() = runTest {
        val windows = FakeWindows(
            mapOf(
                "disc1.t5" to LocalTrackWindow(
                    contentKey = "disc1.t5",
                    flacUri = "content://flac/disc1",
                    startMs = 1_000,
                    endMs = 5_000,
                    gainDb = -3.0,
                    peak = 0.9,
                ),
            ),
        )
        val asm = QueueAssembler(factory, windows) { null }
            .assemble(listOf(row(0, "pianostream://local/disc1.t5")))
        assertThat(asm.items).hasSize(1)
        val item = asm.items.single()
        assertThat(item.clippingConfiguration!!.startPositionMs).isEqualTo(1_000)
        assertThat(item.clippingConfiguration!!.endPositionMs).isEqualTo(5_000)
        assertThat(item.mediaMetadata.extras!!.getString(LocalMediaItemFactory.EXTRA_FLAC_URI))
            .isEqualTo("content://flac/disc1")
        assertThat(windows.calls).containsExactly(listOf("disc1.t5"))
    }

    @Test
    fun dead_local_key_omitted_and_counted_b1() = runTest {
        val windows = FakeWindows(byKey = emptyMap(), missing = 1)
        val asm = QueueAssembler(factory, windows) { null }
            .assemble(
                listOf(
                    row(0, "pianostream://local/dead.key"),
                    row(1, "pianostream://stream/vid22222222"),
                ),
            )
        assertThat(asm.items).hasSize(1)
        assertThat(asm.missingLocal).isEqualTo(2) // provider + per-row fallback
        assertThat(asm.items.single().localConfiguration!!.uri.toString())
            .isEqualTo("pianostream://stream/vid22222222")
    }

    @Test
    fun unparsable_media_id_skipped() = runTest {
        val asm = QueueAssembler(factory, FakeWindows(emptyMap())) { null }
            .assemble(
                listOf(
                    row(0, "spotify:track:xxx"),
                    row(1, "pianostream://stream/vid33333333"),
                ),
            )
        assertThat(asm.items).hasSize(1)
        assertThat(asm.items[0].localConfiguration!!.uri.toString())
            .contains("vid33333333")
    }

    @Test
    fun remote_row_keeps_artwork_uri_f9() = runTest {
        val asm = QueueAssembler(factory, FakeWindows(emptyMap())) { null }
            .assemble(
                listOf(
                    row(0, "pianostream://stream/vid44444444").copy(
                        artworkUri = "https://i.ytimg.com/vi/x/hqdefault.jpg",
                    ),
                ),
            )
        assertThat(asm.items.single().mediaMetadata.artworkUri.toString())
            .isEqualTo("https://i.ytimg.com/vi/x/hqdefault.jpg")
    }

    @Test
    fun empty_queue_yields_empty_assembly() = runTest {
        val asm = QueueAssembler(factory, FakeWindows(emptyMap())) { null }
            .assemble(emptyList())
        assertThat(asm.items).isEmpty()
        assertThat(asm.sourcePositions).isEmpty()
    }

    @Test
    fun window_end_ms_respects_clipping_bounds() = runTest {
        // endMs <= flacDurationMs honoured; endMs > flacDurationMs must omit.
        val w = LocalTrackWindow(
            contentKey = "disc9.t1",
            flacUri = "content://flac/disc9",
            startMs = 0,
            endMs = 400_000, // beyond a 300 s FLAC
            gainDb = null,
            peak = null,
        )
        val asm = QueueAssembler(
            factory,
            FakeWindows(mapOf("disc9.t1" to w)),
        ) { QueueAssembler.DiscArtwork("disc9", null, flacDurationMs = 300_000) }
            .assemble(listOf(row(0, "pianostream://local/disc9.t1")))
        val clip = asm.items.single().clippingConfiguration!!
        assertThat(clip.startPositionMs).isEqualTo(0)
        assertThat(clip.endPositionMs).isEqualTo(C.TIME_END_OF_SOURCE)
    }
}
