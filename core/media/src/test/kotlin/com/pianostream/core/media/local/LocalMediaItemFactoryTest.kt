package com.pianostream.core.media.local

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.local.api.LocalTrackWindow
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CC1/CC11/M3 + E3 — per-track local `MediaItem` clipping bounds and extras.
 */
@RunWith(RobolectricTestRunner::class)
class LocalMediaItemFactoryTest {

    private lateinit var context: Context
    private lateinit var artworkDir: File
    private lateinit var factory: LocalMediaItemFactory

    private fun window(
        contentKey: String = "disc1.t1",
        startMs: Long = 1_234,
        endMs: Long? = 56_789,
        gainDb: Double? = -4.5,
        peak: Double? = 0.98,
    ) = LocalTrackWindow(
        contentKey = contentKey,
        flacUri = "content://flac/disc1",
        startMs = startMs,
        endMs = endMs,
        gainDb = gainDb,
        peak = peak,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        artworkDir = File(context.filesDir, "artwork-test")
        artworkDir.deleteRecursively()
        factory = LocalMediaItemFactory(context, artworkDir)
    }

    @Test
    fun local_media_id_and_uri_stamped() = runTest {
        val item = factory.build(
            window(), "disc1", null, null, "Nocturne", "Chopin", 60_000,
        )
        assertThat(item.mediaId).isEqualTo("pianostream://local/disc1.t1")
        assertThat(item.localConfiguration!!.uri.toString())
            .isEqualTo("pianostream://local/disc1.t1")
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("Nocturne")
        assertThat(item.mediaMetadata.artist.toString()).isEqualTo("Chopin")
    }

    @Test
    fun clipping_bounds_written() = runTest {
        val clip = factory.build(
            window(startMs = 100, endMs = 5_000), "d", null, null, "t", null, null,
        ).clippingConfiguration!!
        assertThat(clip.startPositionMs).isEqualTo(100)
        assertThat(clip.endPositionMs).isEqualTo(5_000)
    }

    @Test
    fun end_ms_omitted_when_null() = runTest {
        val clip = factory.build(
            window(endMs = null), "d", null, null, "t", null, null,
        ).clippingConfiguration!!
        assertThat(clip.endPositionMs).isEqualTo(C.TIME_END_OF_SOURCE)
    }

    // NOTE: the `C.TIME_UNSET` endMs case is unreachable —
    // `LocalTrackWindow`'s own `init` requires `endMs > startMs`, so the
    // defensive `endMs != C.TIME_UNSET` check in the factory is dead-code
    // contract for upstream drift, not a state a caller can construct.

    @Test
    fun end_ms_omitted_when_beyond_flac_duration() = runTest {
        val clip = factory.build(
            window(endMs = 400_000), "d", null, flacDurationMs = 300_000,
            "t", null, null,
        ).clippingConfiguration!!
        assertThat(clip.endPositionMs).isEqualTo(C.TIME_END_OF_SOURCE)
    }

    @Test
    fun end_ms_kept_when_within_flac_duration() = runTest {
        val clip = factory.build(
            window(endMs = 290_000), "d", null, flacDurationMs = 300_000,
            "t", null, null,
        ).clippingConfiguration!!
        assertThat(clip.endPositionMs).isEqualTo(290_000)
    }

    @Test
    fun extras_carry_disc_flac_key_gain_peak() = runTest {
        val extras = factory.build(
            window(), "disc1", null, null, "t", null, null,
        ).mediaMetadata.extras!!
        assertThat(extras.getString(LocalMediaItemFactory.EXTRA_DISC_ID)).isEqualTo("disc1")
        assertThat(extras.getString(LocalMediaItemFactory.EXTRA_FLAC_URI))
            .isEqualTo("content://flac/disc1")
        assertThat(extras.getString(LocalMediaItemFactory.EXTRA_CONTENT_KEY))
            .isEqualTo("disc1.t1")
        assertThat(extras.getDouble(LocalMediaItemFactory.EXTRA_GAIN_DB)).isEqualTo(-4.5)
        assertThat(extras.getDouble(LocalMediaItemFactory.EXTRA_PEAK)).isEqualTo(0.98)
    }

    @Test
    fun gain_peak_absent_when_window_has_none() = runTest {
        val extras = factory.build(
            window(gainDb = null, peak = null), "d", null, null, "t", null, null,
        ).mediaMetadata.extras!!
        assertThat(extras.containsKey(LocalMediaItemFactory.EXTRA_GAIN_DB)).isFalse()
        assertThat(extras.containsKey(LocalMediaItemFactory.EXTRA_PEAK)).isFalse()
    }

    @Test
    fun flac_uri_of_reads_extras() = runTest {
        val item = factory.build(window(), "d", null, null, "t", null, null)
        assertThat(LocalMediaItemFactory.flacUriOf(item)).isEqualTo("content://flac/disc1")
        assertThat(LocalMediaItemFactory.flacUriOf(null)).isNull()
    }

    @Test
    fun artwork_uri_stamped_for_in_process_readers() = runTest {
        // content:// can't resolve under Robolectric so artworkData stays
        // null, but the SAF document URI must still reach the metadata —
        // vinyl labels and queue thumbs read `mediaMetadata.artworkUri`
        // (the F9 boundary is ArtworkUrls.sanitize at the read site).
        val item = factory.build(
            window(), "disc1", "content://art/front", null, "t", null, null,
        )
        assertThat(item.mediaMetadata.artworkUri.toString())
            .isEqualTo("content://art/front")
    }

    @Test
    fun artwork_uri_null_when_disc_has_none() = runTest {
        val item = factory.build(window(), "d", null, null, "t", null, null)
        assertThat(item.mediaMetadata.artworkUri).isNull()
    }

    @Test
    fun cached_artwork_bytes_loaded_from_file() = runTest {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        artworkDir.mkdirs()
        File(artworkDir, "disc9.jpg").writeBytes(jpg)
        val bytes = factory.artworkBytesFor("disc9", "content://art/disc9")
        assertThat(bytes).isEqualTo(jpg)
    }

    @Test
    fun unresolvable_artwork_returns_null() = runTest {
        val bytes = factory.artworkBytesFor("never", "content://definitely/not/here")
        assertThat(bytes).isNull()
    }
}
