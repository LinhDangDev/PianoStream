package com.pianostream.core.media.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fixture-driven mapping (no real network — MockWebServer is loopback);
 * Robolectric because [pickLyricsBody] uses org.json from android.jar.
 */
@RunWith(RobolectricTestRunner::class)
class LrclibClientTest {

    private val query = LyricsQuery(
        mediaId = "pianostream://local/track1",
        title = "Track Title",
        artist = "The Artist",
        durationMs = 214_000L,
        localFileUri = null,
    )

    private fun client(server: MockWebServer) =
        LrclibClient(OkHttpClient(), server.url("/"))

    @Test
    fun syncedLyrics_is_preferred_over_plain() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"id":1,"trackName":"T","syncedLyrics":"[00:01.00]s","plainLyrics":"p"}"""),
            )
            assertThat(client(server).fetch(query)).isEqualTo("[00:01.00]s")
        }
    }

    @Test
    fun falls_back_to_plainLyrics() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"syncedLyrics":null,"plainLyrics":"just words"}"""),
            )
            assertThat(client(server).fetch(query)).isEqualTo("just words")
        }
    }

    @Test
    fun request_carries_track_artist_and_seconds() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            client(server).fetch(query)
            assertThat(server.takeRequest().path).isEqualTo(
                "/api/get?track_name=Track%20Title&artist_name=The%20Artist&duration=214",
            )
        }
    }

    @Test
    fun http_404_and_errors_yield_null() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            val c = client(server)
            assertThat(c.fetch(query)).isNull()
            assertThat(c.fetch(query)).isNull()
            assertThat(c.fetch(query)).isNull()
        }
    }

    @Test
    fun pickLyricsBody_maps_fixture_strings() {
        val c = LrclibClient(OkHttpClient())
        assertThat(c.pickLyricsBody("""{"syncedLyrics":"S","plainLyrics":"P"}""")).isEqualTo("S")
        assertThat(c.pickLyricsBody("""{"syncedLyrics":"","plainLyrics":"P"}""")).isEqualTo("P")
        assertThat(c.pickLyricsBody("""{"plainLyrics":"P"}""")).isEqualTo("P")
        assertThat(c.pickLyricsBody("""{"syncedLyrics":null,"plainLyrics":null}""")).isNull()
        assertThat(c.pickLyricsBody("""{}""")).isNull()
        assertThat(c.pickLyricsBody("""{not json""")).isNull()
    }
}
