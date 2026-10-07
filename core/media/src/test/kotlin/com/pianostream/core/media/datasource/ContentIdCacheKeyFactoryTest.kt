package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CC2 — `pianostream://stream/<videoId>` keys the cache by mediaId (B2: an
 * itag-251→140 fallback or `preferredItag` change still hits the same slot).
 * `local/` URIs never reach this factory (routed out by
 * [SchemeRoutingDataSource]) — if one did, it degrades to `dataSpec.key`/URI
 * instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
class ContentIdCacheKeyFactoryTest {

    private val factory = ContentIdCacheKeyFactory()

    private fun spec(uri: String, key: String? = null): DataSpec {
        val b = DataSpec.Builder().setUri(uri)
        if (key != null) b.setKey(key)
        return b.build()
    }

    @Test
    fun stream_uri_keys_by_media_id() {
        val id = "pianostream://stream/dQw4w9WgXcQ"
        assertThat(factory.buildCacheKey(spec(id))).isEqualTo(id)
    }

    @Test
    fun same_stream_two_calls_same_key() {
        val id = "pianostream://stream/abcdef12345"
        assertThat(factory.buildCacheKey(spec(id)))
            .isEqualTo(factory.buildCacheKey(spec(id)))
    }

    @Test
    fun stream_key_ignores_explicit_key() {
        val id = "pianostream://stream/xyz12345678"
        // mediaId wins over dataSpec.key — cache identity is content identity.
        assertThat(factory.buildCacheKey(spec(id, "itag-251")))
            .isEqualTo(id)
    }

    @Test
    fun non_pianostream_uses_key_then_uri() {
        val http = "https://example.com/file.flac"
        assertThat(factory.buildCacheKey(spec(http, "my-key"))).isEqualTo("my-key")
        assertThat(factory.buildCacheKey(spec(http))).isEqualTo(http)
    }

    @Test
    fun local_uri_never_returns_null() {
        // CacheKeyFactory can't return null in media3 1.11; local URIs are
        // routed around the cache — degrading to the URI here is dead-code
        // contract, not a live cache slot.
        assertThat(factory.buildCacheKey(spec("pianostream://local/abc")))
            .isNotEmpty()
    }
}
