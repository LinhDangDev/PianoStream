package com.pianostream.core.youtube.spotify

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Multi-link paste-field parsing — [SpotifyPlaylistImporter.parseImportLinks]
 * is pure (no network): lines, space/comma separated tokens, dedupe, and
 * per-line invalid flags.
 */
class SpotifyImportLinksTest {

    private val importer = SpotifyPlaylistImporter(OkHttpClient())

    private val idA = "37i9dQZF1DXcBWIGoYBM5M"
    private val idB = "37i9dQZF1DX4JAvHpjipBk"
    private val idC = "37i9dQZF1DX0XUsuxWHRQd"

    @Test
    fun `one link per line parses in order`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/playlist/$idA\n" +
                "https://open.spotify.com/playlist/$idB\n" +
                "https://open.spotify.com/playlist/$idC",
        )
        assertThat(parsed.links.map { it.id }).containsExactly(idA, idB, idC).inOrder()
        assertThat(parsed.links.map { it.line }).containsExactly(1, 2, 3).inOrder()
        assertThat(parsed.invalid).isEmpty()
    }

    @Test
    fun `space and comma separated tokens count as links too`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/playlist/$idA https://open.spotify.com/playlist/$idB\n" +
                "https://open.spotify.com/playlist/$idC,https://open.spotify.com/playlist/$idA",
        )
        // Second occurrence of idA is a duplicate — dropped.
        assertThat(parsed.links.map { it.id }).containsExactly(idA, idB, idC).inOrder()
        assertThat(parsed.invalid).isEmpty()
    }

    @Test
    fun `blank lines and empty input are ignored`() {
        val parsed = importer.parseImportLinks(
            "\n   \nhttps://open.spotify.com/playlist/$idA\n\n",
        )
        assertThat(parsed.links.map { it.id }).containsExactly(idA)
        assertThat(parsed.links.single().line).isEqualTo(3)
        assertThat(parsed.invalid).isEmpty()

        val empty = importer.parseImportLinks("")
        assertThat(empty.links).isEmpty()
        assertThat(empty.invalid).isEmpty()
    }

    @Test
    fun `invalid lines are flagged without blocking valid ones`() {
        val parsed = importer.parseImportLinks(
            "not a link\n" +
                "https://open.spotify.com/playlist/$idA\n" +
                "https://example.com/playlist/$idB",
        )
        assertThat(parsed.links.map { it.id }).containsExactly(idA)
        assertThat(parsed.invalid.map { it.line }).containsExactly(1, 3).inOrder()
        assertThat(parsed.invalid[0].text).isEqualTo("not a link")
    }

    @Test
    fun `a mixed line keeps the good token and flags the bad`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/playlist/$idA, garbage",
        )
        assertThat(parsed.links.map { it.id }).containsExactly(idA)
        assertThat(parsed.invalid.map { it.line }).containsExactly(1)
        assertThat(parsed.invalid.single().text).isEqualTo("garbage")
    }

    @Test
    fun `the same playlist via different link shapes dedupes to one`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/playlist/$idA\n" +
                "https://open.spotify.com/playlist/$idA?si=xyz\n" +
                "spotify:playlist:$idA\n" +
                idA,
        )
        assertThat(parsed.links).hasSize(1)
        assertThat(parsed.links.single().id).isEqualTo(idA)
        assertThat(parsed.invalid).isEmpty()
    }

    @Test
    fun `intl urls and raw ids parse`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/intl-de/playlist/$idA\n$idB",
        )
        assertThat(parsed.links.map { it.id }).containsExactly(idA, idB).inOrder()
        assertThat(parsed.invalid).isEmpty()
    }
}

class SpotifyImportLinkFormsTest {

    private val importer = SpotifyPlaylistImporter(OkHttpClient())

    private val idA = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun `album track and short links parse to their kinds`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv\n" +
                "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc\n" +
                "https://spotify.link/AbC12DeF\n" +
                "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
        )
        assertThat(parsed.invalid).isEmpty()
        // the uri-form track dedupes with the url-form track above it
        assertThat(parsed.links.map { it.kind }).containsExactly(
            SpotifyPlaylistImporter.EntityKind.ALBUM,
            SpotifyPlaylistImporter.EntityKind.TRACK,
            SpotifyPlaylistImporter.EntityKind.SHORT,
        ).inOrder()
        assertThat(parsed.links[0].id).isEqualTo("4LH4d3cOWNNsVw41Gqt2kv")
        assertThat(parsed.links[1].id).isEqualTo("4uLU6hMCjMI75M1A2tKUQC")
        assertThat(parsed.links[2].id).contains("spotify.link/")
    }

    @Test
    fun `intl album url and same entity via two shapes dedupe`() {
        val parsed = importer.parseImportLinks(
            "https://open.spotify.com/intl-vn/album/4LH4d3cOWNNsVw41Gqt2kv\n" +
                "spotify:album:4LH4d3cOWNNsVw41Gqt2kv\n" +
                "https://open.spotify.com/playlist/$idA",
        )
        // album id dedupes across url/uri shapes; playlist still counts
        assertThat(parsed.links).hasSize(2)
        assertThat(parsed.invalid).isEmpty()
    }
}
