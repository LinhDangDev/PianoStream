package com.pianostream.core.youtube.innertube

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeItem
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Fixture-driven InnerTube contract tests (CC3/F11/F12): search returns typed
 * items with valid ids only; malformed ids are dropped at the boundary;
 * suggestions/browse round-trip through the recorded fixtures.
 */
class CueFreeSearchTest {

    private lateinit var server: MockWebServer
    private lateinit var client: InnerTubeClientImpl

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = InnerTubeClientImpl(base = server.url("/youtubei/v1").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `search returns valid items and drops malformed videoIds`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("search_chopin.json")))
        val page = client.search("Chopin Nocturne", SearchFilter.SONGS)

        assertThat(page.sections).isNotEmpty()
        val items = page.sections.flatMap { it.items }
        assertThat(items.size).isAtLeast(3) // 2 songs + 1 video
        for (item in items) {
            assertThat(item.videoId).matches("^[A-Za-z0-9_-]{11}$")
            assertThat(item.title).isNotEmpty()
        }
        // F11: the "AAAA/itag" item was dropped at the boundary.
        assertThat(items.map { it.title }).doesNotContain("BAD ITEM — malformed id")
        // Duration parsed from clock text for the first item.
        val nocturne = items.first { it.title == "Nocturne Op. 9 No. 2" }
        assertThat(nocturne.durationMs).isEqualTo(272_000)
        assertThat(nocturne.kind).isEqualTo(YouTubeItem.Kind.TRACK)
        assertThat(nocturne.artworkUri).startsWith("https://")
        // Continuation captured.
        assertThat(page.continuation).isEqualTo("tok_chopin_page2")
    }

    @Test
    fun `search sends WEB_REMIX context`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("search_chopin.json")))
        client.search("x", SearchFilter.ALL)
        val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertThat(recorded.getHeader("X-YouTube-Client-Name")).isEqualTo("67")
        assertThat(recorded.getHeader("X-Goog-Visitor-Id")).isNotEmpty()
        assertThat(recorded.path).contains("/youtubei/v1/search")
    }

    @Test
    fun `suggestions returns non-empty strings`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("suggestions_chop.json")))
        val out = client.suggestions("chop")
        assertThat(out.size).isAtLeast(3)
        out.forEach { assertThat(it).isNotEmpty() }
        assertThat(out).contains("chopin nocturne")
    }

    @Test
    fun `suggestions is only invoked when the caller calls it`() = runTest {
        // F12: the local-scope gate lives at the consumer (Phase 4); this
        // client never calls get_search_suggestions on its own — assert the
        // only endpoint hit during a search is /search.
        server.enqueue(MockResponse().setBody(fixture("search_chopin.json")))
        client.search("q", SearchFilter.SONGS)
        assertThat(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path).contains("/search")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `browse returns sections and continuation`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("browse_home.json")))
        val page = client.browse("FEmusic_home")
        assertThat(page.sections.size).isAtLeast(3)
        assertThat(page.continuation).isEqualTo("tok_home_page2")
    }

    @Test
    fun `continuation fetch sends the token`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("browse_home.json")))
        server.enqueue(MockResponse().setBody(fixture("browse_home.json")))
        val first = client.browse("FEmusic_home")
        client.browse("FEmusic_home", continuation = first.continuation)
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertThat(second.body.readUtf8()).contains("tok_home_page2")
    }
}
