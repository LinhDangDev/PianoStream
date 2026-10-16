package com.pianostream.core.youtube.spotify

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Fixture-driven importer tests — `spotify/embed_playlist.html` is a recorded
 * `open.spotify.com/embed/playlist/…` page (Today's Top Hits, trackList
 * trimmed to 6 rows) so the parser is pinned against Spotify's real shape.
 */
class SpotifyPlaylistImporterTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("spotify/$name")!!
            .readBytes().decodeToString()

    private fun importer(base: String) =
        SpotifyPlaylistImporter(OkHttpClient(), embedBase = base)

    /** Wraps an entity object in the baked-in props.pageProps path + script tag. */
    private fun wrapNextData(entityJson: String) =
        """<script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"pageProps":{"state":{"data":{"entity":""" +
            entityJson +
            """}}}}}""" +
            "</script>"

    // --- link parsing ---------------------------------------------------

    @Test
    fun `parsePlaylistId accepts common link shapes`() {
        val importer = importer(server.url("/").toString().trimEnd('/'))
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertThat(importer.parsePlaylistId("https://open.spotify.com/playlist/$id"))
            .isEqualTo(id)
        assertThat(
            importer.parsePlaylistId("https://open.spotify.com/playlist/$id?si=abc123"),
        ).isEqualTo(id)
        assertThat(
            importer.parsePlaylistId("https://open.spotify.com/intl-de/playlist/$id"),
        ).isEqualTo(id)
        assertThat(
            importer.parsePlaylistId(
                "https://open.spotify.com/intl-vn/playlist/$id?si=x&utm_source=y",
            ),
        ).isEqualTo(id)
        assertThat(importer.parsePlaylistId("spotify:playlist:$id")).isEqualTo(id)
        assertThat(importer.parsePlaylistId(id)).isEqualTo(id) // bare id
        assertThat(
            importer.parsePlaylistId("paste https://open.spotify.com/playlist/$id here"),
        ).isEqualTo(id)
    }

    @Test
    fun `parsePlaylistId rejects non-playlist input`() {
        val importer = importer(server.url("/").toString().trimEnd('/'))
        assertThat(importer.parsePlaylistId("")).isNull()
        assertThat(importer.parsePlaylistId("hello world")).isNull()
        assertThat(
            importer.parsePlaylistId("https://open.spotify.com/track/3zOYmiNOuyDL0ClxJcEJ0T"),
        ).isNull()
        assertThat(
            importer.parsePlaylistId("https://example.com/playlist/37i9dQZF1DXcBWIGoYBM5M"),
        ).isNull()
    }

    // --- embed parsing (recorded fixture) --------------------------------

    @Test
    fun `parse reads name and track list from recorded embed page`() {
        val parsed = SpotifyEmbedParser.parse(fixture("embed_playlist.html"))
        assertThat(parsed).isNotNull()
        parsed!!
        assertThat(parsed.name).isEqualTo("Today’s Top Hits")
        assertThat(parsed.tracks).hasSize(6)
        assertThat(parsed.totalCount).isEqualTo(6)
        val first = parsed.tracks.first()
        assertThat(first.title).isEqualTo("Stop The Wedding!")
        assertThat(first.artist).isEqualTo("Ashe")
        assertThat(first.durationMs).isEqualTo(198840L)
        // Multi-artist subtitles stay comma-joined.
        assertThat(parsed.tracks[5].artist).isEqualTo("KAROL G, Judeline, rusowsky")
        assertThat(parsed.coverUrl).startsWith("https://")
    }

    @Test
    fun `parse deep-walks when entity is not at the baked-in path`() {
        val moved = """<html><body><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"nested":{"elsewhere":{"entity":{
                "type":"playlist","name":"Loose",
                "trackList":[{"title":"A","subtitle":"B","duration":1000}]
            }}}}}}</script></body></html>"""
        val parsed = SpotifyEmbedParser.parse(moved)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.name).isEqualTo("Loose")
        assertThat(parsed.tracks).hasSize(1)
        assertThat(parsed.tracks[0].title).isEqualTo("A")
        assertThat(parsed.tracks[0].artist).isEqualTo("B")
        assertThat(parsed.tracks[0].durationMs).isEqualTo(1000L)
    }

    @Test
    fun `parse reads the legacy tracks items shape`() {
        val entity =
            """{"name":"Legacy","tracks":{"items":[{"track":{"name":"Old Song",""" +
                """"artists":[{"name":"X"},{"name":"Y"}],"duration_ms":61000,""" +
                """"album":{"images":[{"url":"https://i.scdn.co/x.jpg"}]}}}]}}"""
        val legacy = wrapNextData(entity)
        val parsed = SpotifyEmbedParser.parse(legacy)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.name).isEqualTo("Legacy")
        assertThat(parsed.tracks).hasSize(1)
        val t = parsed.tracks[0]
        assertThat(t.title).isEqualTo("Old Song")
        assertThat(t.artist).isEqualTo("X, Y")
        assertThat(t.durationMs).isEqualTo(61000L)
        assertThat(t.artworkUrl).isEqualTo("https://i.scdn.co/x.jpg")
    }

    @Test
    fun `parse returns null when no track json exists`() {
        assertThat(SpotifyEmbedParser.parse("<html><body>oops</body></html>"))
            .isNull()
        assertThat(
            SpotifyEmbedParser.parse(
                """<script id="__NEXT_DATA__" type="application/json">{"props":{}}</script>""",
            ),
        ).isNull()
    }

    @Test
    fun `parse truncates at MAX_TRACKS`() {
        val many = (1..150).joinToString(",") {
            """{"title":"T$it","subtitle":"S","duration":$it}"""
        }
        val html = wrapNextData("""{"name":"Big","trackList":[$many]}""")
        val parsed = SpotifyEmbedParser.parse(html)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.tracks).hasSize(SpotifyPlaylistImporter.MAX_TRACKS)
        assertThat(parsed.totalCount).isEqualTo(150)
    }

    // --- end-to-end over MockWebServer ------------------------------------

    @Test
    fun `import fetches the embed page for the id in the link`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(fixture("embed_playlist.html")),
        )
        val base = server.url("/").toString().trimEnd('/')
        val playlist = importer(base).import(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
        )
        assertThat(playlist.id).isEqualTo("37i9dQZF1DXcBWIGoYBM5M")
        assertThat(playlist.name).isEqualTo("Today’s Top Hits")
        assertThat(playlist.tracks).hasSize(6)
        assertThat(server.takeRequest().path)
            .isEqualTo("/embed/playlist/37i9dQZF1DXcBWIGoYBM5M")
    }

    @Test
    fun `import maps 404 to NOT_FOUND_OR_PRIVATE`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val base = server.url("/").toString().trimEnd('/')
        try {
            importer(base).import("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
            error("expected throw")
        } catch (e: SpotifyPlaylistImporter.SpotifyImportException) {
            assertThat(e.kind)
                .isEqualTo(SpotifyPlaylistImporter.SpotifyImportException.Kind.NOT_FOUND_OR_PRIVATE)
        }
    }

    @Test
    fun `import rejects a non-spotify link without any request`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        try {
            importer(base).import("https://example.com/playlist/3zOYmiNOuyDL0ClxJcEJ0T")
            error("expected throw")
        } catch (e: SpotifyPlaylistImporter.SpotifyImportException) {
            assertThat(e.kind)
                .isEqualTo(SpotifyPlaylistImporter.SpotifyImportException.Kind.INVALID_URL)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `import fetches the track embed for a track link`() = runTest {
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setBody(
                """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"track","title":"Song","duration":1000,"artists":[{"name":"A"}]}}}}}}</script>""",
            ),
        )
        val base = server.url("/").toString().trimEnd('/')
        val playlist = importer(base)
            .import("https://open.spotify.com/track/3zOYmiNOuyDL0ClxJcEJ0T")
        assertThat(server.takeRequest().path).isEqualTo("/embed/track/3zOYmiNOuyDL0ClxJcEJ0T")
        assertThat(playlist.tracks).hasSize(1)
        assertThat(playlist.tracks.single().title).isEqualTo("Song")
    }
}
