package com.pianostream.core.youtube.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CC4/F13: `ResolvedStream.toString()` prints only
 * `videoId`/`itag`/`clientId`/`expiresAtEpochMs` — the signed `googlevideo`
 * URL (carrying `pot`/`sig`/`n`) and request headers must never leak.
 */
class ResolvedStreamRedactionTest {

    private fun stream() = ResolvedStream(
        videoId = "dQw4w9WgXcQ",
        url = "https://rr1---sn-x.googlevideo.com/videoplayback?pot=AAA&sig=BBB&n=CCC&lsig=DDD&expire=999&ip=1.2.3.4&cpn=EEE&range=0-1&itag=251",
        itag = 251,
        mimeType = "audio/webm; codecs=\"opus\"",
        bitrate = 160000,
        contentLength = 1234567L,
        expiresAtEpochMs = 4_000_000_000_000L,
        expiresInSeconds = 21540L,
        headers = mapOf("User-Agent" to "x", "Authorization" to "Bearer secret-token"),
        clientId = "ANDROID_VR/1.65.10",
        resolvedAtUptimeMs = 1_000L,
        expiresAtUptimeMs = 1_000L + 21_540_000L,
    )

    @Test
    fun `toString never leaks url or headers`() {
        val s = stream().toString()
        assertThat(s).doesNotContain("pot=")
        assertThat(s).doesNotContain("sig=")
        assertThat(s).doesNotContain("n=CCC")
        assertThat(s).doesNotContain("googlevideo")
        assertThat(s).doesNotContain("https://")
        assertThat(s).doesNotContain("Authorization")
        assertThat(s).doesNotContain("secret-token")
        assertThat(s).doesNotContain("User-Agent")

        // Only the safe fields appear.
        assertThat(s).contains("dQw4w9WgXcQ")
        assertThat(s).contains("251")
        assertThat(s).contains("ANDROID_VR/1.65.10")
        assertThat(s).contains("4000000000000")
    }

    @Test
    fun `isFresh honors uptime clock and margin`() {
        val s = stream()
        assertThat(s.isFresh(1_000L)).isTrue()
        // Inside the 300 s margin of the deadline → not fresh.
        assertThat(s.isFresh(21_540_000L + 1_000L - 200_000L)).isFalse()
        assertThat(s.isFresh(21_540_000L + 1_000L)).isFalse()
    }
}
