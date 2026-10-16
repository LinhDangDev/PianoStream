package com.pianostream.core.youtube.spotify

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Embed-page parsing for non-playlist entity kinds. */
class SpotifyEmbedParserKindsTest {

    private fun page(entityJson: String) =
        """<html><script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"pageProps":{"state":{"data":{"entity":$entityJson}}}}}""" +
            "</script></html>"

    @Test
    fun `album embed yields its trackList`() {
        val html = page(
            """{"type":"album","name":"Thriller","trackList":[""" +
                """{"title":"Wanna Be Startin' Somethin'","subtitle":"Michael Jackson","duration":363000},""" +
                """{"title":"Thriller","subtitle":"Michael Jackson","duration":357000}]}""",
        )
        val parsed = SpotifyEmbedParser.parse(html, SpotifyPlaylistImporter.EntityKind.ALBUM)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.name).isEqualTo("Thriller")
        assertThat(parsed.tracks).hasSize(2)
        assertThat(parsed.tracks[0].artist).isEqualTo("Michael Jackson")
        assertThat(parsed.tracks[0].durationMs).isEqualTo(363000)
    }

    @Test
    fun `track embed yields a one-track playlist`() {
        val html = page(
            """{"type":"track","title":"Never Gonna Give You Up","duration":213573,""" +
                """"artists":[{"name":"Rick Astley"}]}""",
        )
        val parsed = SpotifyEmbedParser.parse(html, SpotifyPlaylistImporter.EntityKind.TRACK)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.name).isEqualTo("Never Gonna Give You Up")
        assertThat(parsed.tracks).hasSize(1)
        assertThat(parsed.tracks[0].artist).isEqualTo("Rick Astley")
        assertThat(parsed.tracks[0].durationMs).isEqualTo(213573)
    }
}
