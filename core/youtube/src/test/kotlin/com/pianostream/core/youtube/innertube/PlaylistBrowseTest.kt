package com.pianostream.core.youtube.innertube

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.YouTubeItem
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Playlist browse contract (`VL<playlistId>`): the track list lives in a
 * `musicPlaylistShelfRenderer` (not the shelf kinds search/home use), the
 * page title sits in `microformatDataRenderer` (the first bare `title` in
 * the body is the sort menu's), and >100-track pages return their next
 * batch as bare `appendContinuationItemsAction.continuationItems` with no
 * shelf wrapper at all. Fixtures are trimmed from a real WEB_REMIX
 * response.
 */
class PlaylistBrowseTest {

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
    fun `playlist shelf yields playable tracks with valid videoIds`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("playlist_browse.json")))
        val page = client.browse("VLPLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj")

        val items = page.sections.flatMap { it.items }
        assertThat(items).hasSize(2)
        for (item in items) {
            assertThat(item.videoId).matches("^[A-Za-z0-9_-]{11}$")
            assertThat(item.kind).isIn(listOf(YouTubeItem.Kind.TRACK, YouTubeItem.Kind.VIDEO))
            assertThat(item.title).isNotEmpty()
            assertThat(item.artworkUri).startsWith("https://")
        }
        assertThat(items.map { it.videoId }).contains("ekr2nIex040")
        val apt = items.first { it.title == "APT." }
        assertThat(apt.durationMs).isEqualTo(174_000)
        assertThat(apt.subtitle).contains("ROS")
    }

    @Test
    fun `playlist title prefers microformat over the sort menu title`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("playlist_browse.json")))
        val page = client.browse("VLPLx")
        assertThat(page.title)
            .isEqualTo("Pop Music Playlist - Timeless Pop Songs (Updated Weekly 2026)")
        assertThat(page.title).isNotEqualTo("Sort")
    }

    @Test
    fun `page one carries the continuation token`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("playlist_browse.json")))
        val page = client.browse("VLPLx")
        assertThat(page.continuation).isEqualTo("tok_playlist_page2")
    }

    @Test
    fun `continuationItems parse into playable tracks without a shelf`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("playlist_continuation.json")))
        val page = client.browse("VLPLx", continuation = "tok_playlist_page2")

        val items = page.sections.flatMap { it.items }
        assertThat(items).hasSize(1)
        assertThat(items[0].videoId).matches("^[A-Za-z0-9_-]{11}$")
        // Next page token survives so the caller can keep paging.
        assertThat(page.continuation).isEqualTo("tok_playlist_page3")
    }
}
