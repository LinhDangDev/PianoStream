package com.pianostream.core.youtube.cache

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.player.PlayerResponseParser
import org.junit.Test

/**
 * F17: expire clamp `min(parsed, now+6h)`; missing/unparseable → `now+1h`;
 * `parsed <= now+300s` never cached; first-403 force-evicts.
 */
class ExpireClampTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `parsed expire clamps to now+6h when far future`() {
        val year3000 = "https://g/x?expire=32503680000" // epoch s, year 3000
        val clamped = PlayerResponseParser.clampedExpiry(year3000, now)
        assertThat(clamped).isEqualTo(now + PlayerResponseParser.MAX_CLAMP_MS)
    }

    @Test
    fun `parsed expire used when inside clamp window`() {
        val soon = (now / 1000) + 3600
        val url = "https://g/x?expire=$soon"
        assertThat(PlayerResponseParser.clampedExpiry(url, now))
            .isEqualTo(soon * 1000)
    }

    @Test
    fun `missing expire defaults to now+1h`() {
        assertThat(PlayerResponseParser.clampedExpiry("https://g/x?a=1", now))
            .isEqualTo(now + PlayerResponseParser.DEFAULT_TTL_MS)
    }

    @Test
    fun `non-numeric expire defaults to now+1h`() {
        assertThat(PlayerResponseParser.clampedExpiry("https://g/x?expire=abc", now))
            .isEqualTo(now + PlayerResponseParser.DEFAULT_TTL_MS)
    }

    @Test
    fun `expire in the past is below the cache floor`() {
        val past = "https://g/x?expire=${(now / 1000) - 10}"
        assertThat(PlayerResponseParser.isBelowCacheFloor(past, now)).isTrue()
    }

    @Test
    fun `expire inside 300s margin is below the cache floor`() {
        val soon = "https://g/x?expire=${(now / 1000) + 200}"
        assertThat(PlayerResponseParser.isBelowCacheFloor(soon, now)).isTrue()
        val beyond = "https://g/x?expire=${(now / 1000) + 400}"
        assertThat(PlayerResponseParser.isBelowCacheFloor(beyond, now)).isFalse()
    }

    private var uptime = 0L
    private val cache = LruStreamUrlCache(uptimeMs = { uptime })

    private fun stream(itag: Int, expiresInMs: Long): ResolvedStream {
        val resolved = uptime
        return ResolvedStream(
            videoId = "dQw4w9WgXcQ",
            url = "https://g/x",
            itag = itag,
            mimeType = "audio/webm",
            bitrate = 1,
            contentLength = 1,
            expiresAtEpochMs = now + expiresInMs,
            expiresInSeconds = expiresInMs / 1000,
            headers = emptyMap(),
            clientId = "C",
            resolvedAtUptimeMs = resolved,
            expiresAtUptimeMs = resolved + expiresInMs,
        )
    }

    @Test
    fun `put rejects an entry already inside the 300s margin`() {
        assertThat(cache.put(stream(251, 200_000))).isFalse()
        assertThat(cache.size).isEqualTo(0)
    }

    @Test
    fun `put accepts a fresh entry and get returns it`() {
        assertThat(cache.put(stream(251, 600_000))).isTrue()
        assertThat(cache.get("dQw4w9WgXcQ", "C", 251, uptime)).isNotNull()
    }

    @Test
    fun `get evicts once the margin is crossed`() {
        cache.put(stream(251, 400_000))
        uptime += 200_000 // remaining 200s < 300s margin
        assertThat(cache.get("dQw4w9WgXcQ", "C", 251, uptime)).isNull()
        assertThat(cache.size).isEqualTo(0)
    }

    @Test
    fun `first 403 force-evicts the key`() {
        cache.put(stream(251, 600_000))
        cache.evict("dQw4w9WgXcQ", "C", 251)
        assertThat(cache.get("dQw4w9WgXcQ", "C", 251, uptime)).isNull()
    }
}
