package com.pianostream.app.fake

import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import com.pianostream.core.youtube.api.YouTubePage
import com.pianostream.core.youtube.api.YouTubeSection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic in-memory `YouTubeClient` (stub): fixed items, counts calls
 * so F12 leak tests can assert zero client traffic under `localOnlySearch`.
 */
@Singleton
public class FakeYouTubeClient @Inject constructor() : YouTubeClient {

    public var searchCalls: Int = 0
        private set
    public var browseCalls: Int = 0
        private set
    public var suggestionCalls: Int = 0
        private set

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        continuation: String?,
    ): YouTubePage {
        searchCalls++
        return YouTubePage(
            title = "Results",
            sections = listOf(
                YouTubeSection(
                    title = "Songs",
                    items = listOf(
                        track("aaaaaaaaaaa", "Fake YouTube song about $query"),
                        track("bbbbbbbbbbb", "Another song about $query"),
                    ),
                ),
            ),
            continuation = null,
        )
    }

    override suspend fun browse(browseId: String, continuation: String?): YouTubePage {
        browseCalls++
        return YouTubePage(
            title = "Home",
            sections = listOf(
                YouTubeSection(
                    title = "Fake shelf",
                    items = listOf(
                        track("ccccccccccc", "Fake YouTube pick"),
                        YouTubeItem(
                            kind = YouTubeItem.Kind.ALBUM,
                            videoId = null,
                            browseId = "VLfake",
                            title = "Fake album",
                            subtitle = "Fake artist",
                            artworkUri = "https://i.ytimg.com/fake.jpg",
                            durationMs = null,
                        ),
                    ),
                ),
            ),
            continuation = null,
        )
    }

    override suspend fun suggestions(query: String): List<String> {
        suggestionCalls++
        return listOf("$query sonata", "$query nocturne")
    }

    private fun track(videoId: String, title: String): YouTubeItem = YouTubeItem(
        kind = YouTubeItem.Kind.TRACK,
        videoId = videoId,
        browseId = null,
        title = title,
        subtitle = "Fake channel",
        artworkUri = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        durationMs = 210_000,
    )
}
