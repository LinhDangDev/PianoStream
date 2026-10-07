package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scheme routing contract: `pianostream://local/` bypasses `SimpleCache` —
 * everything else, including `pianostream://video/` muxed streams, takes the
 * cached branch like `stream/`.
 */
@RunWith(RobolectricTestRunner::class)
class SchemeRoutingDataSourceTest {

    private class RecordingSource : DataSource {
        var openCount = 0
        override fun open(dataSpec: DataSpec): Long {
            openCount++
            return 1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri() = null
        override fun close() {}
        override fun addTransferListener(t: TransferListener) {}
    }

    private lateinit var cached: RecordingSource
    private lateinit var uncached: RecordingSource
    private lateinit var router: SchemeRoutingDataSource

    @Before
    fun setUp() {
        cached = RecordingSource()
        uncached = RecordingSource()
        router = SchemeRoutingDataSource(cached = cached, uncached = uncached)
    }

    private fun specOf(uri: String) = DataSpec.Builder().setUri(uri).build()

    @Test
    fun video_uri_uses_cached_branch_like_stream() {
        router.open(specOf("pianostream://video/dQw4w9WgXcQ"))
        assertThat(cached.openCount).isEqualTo(1)
        assertThat(uncached.openCount).isEqualTo(0)
    }

    @Test
    fun stream_uri_uses_cached_branch() {
        router.open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(cached.openCount).isEqualTo(1)
        assertThat(uncached.openCount).isEqualTo(0)
    }

    @Test
    fun local_uri_bypasses_cache() {
        router.open(specOf("pianostream://local/disc3.t2"))
        assertThat(uncached.openCount).isEqualTo(1)
        assertThat(cached.openCount).isEqualTo(0)
    }

    @Test
    fun foreign_uri_uses_cached_branch() {
        router.open(specOf("https://cdn.example.com/x.flac"))
        assertThat(cached.openCount).isEqualTo(1)
        assertThat(uncached.openCount).isEqualTo(0)
    }
}
