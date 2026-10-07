package com.pianostream.core.youtube.api

/**
 * The on-device YouTube Music client (Interface Freeze — Phase 1b implements
 * `InnerTubeClientImpl` over the `music.youtube.com` `youtubei/v1/` endpoints with the
 * `WEB_REMIX` client, id 67).
 *
 * Contracts baked in:
 *  - **CC3/F11** — every emitted [YouTubeItem.videoId] satisfies
 *    `^[A-Za-z0-9_-]{11}$`; non-conforming items are dropped at the parser
 *    boundary, never reach [com.pianostream.core.data.uri.PianoStreamUri].
 *  - **SEC-F12** — `visitorData` is generated per install and held in memory
 *    only (never DataStore/disk); callers must not invoke [suggestions] while
 *    a local-scope filter is active, and `localOnlySearch=true` means zero
 *    calls to this client at all (consumer-side gate, Phase 4).
 *  - **CC4** — failures surface as [ExtractionException] with redacted
 *    messages.
 *  - This module never writes audio bytes (F18).
 */
public interface YouTubeClient {

    /**
     * `POST /youtubei/v1/search` under [filter]; [continuation] fetches the
     * next page of a previous result (`null` = first page).
     */
    public suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        continuation: String? = null,
    ): YouTubePage

    /**
     * `POST /youtubei/v1/browse`. Known ids: `FEmusic_home`,
     * `FEmusic_explore`, `FEmusic_new_releases_albums`, `VL<playlistId>`.
     */
    public suspend fun browse(
        browseId: String,
        continuation: String? = null,
    ): YouTubePage

    /**
     * `POST /youtubei/v1/get_search_suggestions`. Callers MUST skip this while
     * a local-scope filter is active (F12, leak-tested).
     */
    public suspend fun suggestions(query: String): List<String>
}

/**
 * One search/browse page. Search responses map to a single section; browse
 * responses map each shelf to a section. [continuation] null = last page.
 */
public data class YouTubePage(
    val title: String?,
    val sections: List<YouTubeSection>,
    val continuation: String?,
)

/** A named shelf inside a [YouTubePage]. */
public data class YouTubeSection(
    val title: String?,
    val items: List<YouTubeItem>,
)

/**
 * One YouTube Music entity. [videoId] is present on TRACK/VIDEO items only and
 * always satisfies `^[A-Za-z0-9_-]{11}$` (F11). [browseId] carries
 * `VL<playlistId>`/album/artist targets for follow-up [YouTubeClient.browse]
 * calls. `itag` never appears here (CC2).
 */
public data class YouTubeItem(
    val kind: Kind,
    val videoId: String?,
    val browseId: String?,
    val title: String,
    val subtitle: String?,
    val artworkUri: String?,
    val durationMs: Long?,
) {
    public enum class Kind { TRACK, VIDEO, ALBUM, ARTIST, PLAYLIST }
}
