package com.pianostream.core.youtube.spotify

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import com.pianostream.core.youtube.api.YouTubePage
import com.pianostream.core.youtube.api.YouTubeSection
import com.pianostream.core.youtube.spotify.SpotifyImportRunner.Match
import com.pianostream.core.youtube.spotify.SpotifyPlaylistImporter.ImportedTrack
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Matcher scoring tests over a fake [YouTubeClient] — no network. Pins the
 * duration-tolerance veto, TRACK-over-VIDEO preference, and the per-track
 * result alignment of [SpotifyImportRunner.run].
 */
class SpotifyImportRunnerTest {

    private fun item(
        id: String,
        kind: YouTubeItem.Kind = YouTubeItem.Kind.TRACK,
        durationMs: Long? = null,
        title: String = "t-$id",
    ) = YouTubeItem(
        kind = kind,
        videoId = id,
        browseId = null,
        title = title,
        subtitle = null,
        artworkUri = null,
        durationMs = durationMs,
    )

    private fun pageOf(vararg items: YouTubeItem) = YouTubePage(
        title = null,
        sections = listOf(YouTubeSection(title = null, items = items.toList())),
        continuation = null,
    )

    /** Search stub — [pages] are served by query substring, else [default]. */
    private class FakeClient(
        val pages: Map<String, YouTubePage> = emptyMap(),
        val default: YouTubePage = YouTubePage(null, emptyList(), null),
        val fail: Throwable? = null,
    ) : YouTubeClient {
        val queries = mutableListOf<String>()
        override suspend fun search(
            query: String,
            filter: SearchFilter,
            continuation: String?,
        ): YouTubePage {
            queries += query
            fail?.let { throw it }
            return pages.entries.firstOrNull { query.contains(it.key) }?.value
                ?: default
        }

        override suspend fun browse(browseId: String, continuation: String?) =
            default

        override suspend fun suggestions(query: String) = emptyList<String>()
    }

    private fun track(
        title: String,
        artist: String? = "Someone",
        durationMs: Long? = null,
    ) = ImportedTrack(title = title, artist = artist, durationMs = durationMs, artworkUrl = null)

    @Test
    fun `query is first-artist + title`() = runTest {
        val client = FakeClient()
        SpotifyImportRunner(client).matchOne(
            track(title = "BbY WOW", artist = "KAROL G, Judeline, rusowsky"),
        )
        assertThat(client.queries).containsExactly("KAROL G BbY WOW")
    }

    @Test
    fun `first TRACK wins when duration is unknown`() = runTest {
        val client = FakeClient(
            default = pageOf(
                item("AAAAAAAAAAA"),
                item("BBBBBBBBBBB"),
            ),
        )
        val m = SpotifyImportRunner(client).matchOne(track("x"))
        assertThat((m as Match.Matched).item.videoId).isEqualTo("AAAAAAAAAAA")
    }

    @Test
    fun `within-tolerance duration wins over the first outlier`() = runTest {
        // Expected 200s; first TRACK is a 400s outlier, second is 205s.
        val client = FakeClient(
            default = pageOf(
                item("OUTLIER____", durationMs = 400_000),
                item("CLOSE______", durationMs = 205_000),
            ),
        )
        val m = SpotifyImportRunner(client)
            .matchOne(track("x", durationMs = 200_000))
        assertThat((m as Match.Matched).item.videoId).isEqualTo("CLOSE______")
    }

    @Test
    fun `falls back to pool first when nothing is within tolerance`() = runTest {
        val client = FakeClient(
            default = pageOf(
                item("AAAAAAAAAAA", durationMs = 500_000),
                item("BBBBBBBBBBB", durationMs = 600_000),
            ),
        )
        val m = SpotifyImportRunner(client)
            .matchOne(track("x", durationMs = 200_000))
        assertThat((m as Match.Matched).item.videoId).isEqualTo("AAAAAAAAAAA")
    }

    @Test
    fun `VIDEOs are only used when no TRACK exists`() = runTest {
        val client = FakeClient(
            default = pageOf(
                item("VIDEO______", kind = YouTubeItem.Kind.VIDEO),
                item("TRACK______", kind = YouTubeItem.Kind.TRACK),
            ),
        )
        val m = SpotifyImportRunner(client).matchOne(track("x"))
        assertThat((m as Match.Matched).item.videoId).isEqualTo("TRACK______")
    }

    @Test
    fun `only items with a videoId are candidates`() = runTest {
        val noId = YouTubeItem(
            kind = YouTubeItem.Kind.ALBUM,
            videoId = null,
            browseId = "MPREb_abc",
            title = "album",
            subtitle = null,
            artworkUri = null,
            durationMs = null,
        )
        val client = FakeClient(default = pageOf(noId))
        val m = SpotifyImportRunner(client).matchOne(track("x"))
        assertThat(m).isEqualTo(Match.NotFound)
    }

    @Test
    fun `empty page is NotFound, throwing client is Failed`() = runTest {
        val empty = SpotifyImportRunner(FakeClient()).matchOne(track("x"))
        assertThat(empty).isEqualTo(Match.NotFound)

        val failed = SpotifyImportRunner(FakeClient(fail = IOException("down")))
            .matchOne(track("x"))
        assertThat(failed).isInstanceOf(Match.Failed::class.java)
    }

    @Test
    fun `run returns results aligned with input order`() = runTest {
        val client = FakeClient(
            pages = mapOf(
                "hit" to pageOf(item("HIT________")),
            ),
        )
        val tracks = listOf(track("hit"), track("miss"), track("hit too"))
        // "hit too" contains "hit" → also matches; "miss" → empty default.
        val seen = mutableListOf<Int>()
        val results = SpotifyImportRunner(client, parallelism = 2)
            .run(tracks) { i, _ -> seen += i }
        assertThat(results).hasSize(3)
        assertThat(results[0]).isInstanceOf(Match.Matched::class.java)
        assertThat(results[1]).isEqualTo(Match.NotFound)
        assertThat(results[2]).isInstanceOf(Match.Matched::class.java)
        assertThat(seen.sorted()).containsExactly(0, 1, 2)
    }
}
