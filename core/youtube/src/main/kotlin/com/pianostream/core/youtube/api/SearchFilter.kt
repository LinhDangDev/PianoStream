package com.pianostream.core.youtube.api

/**
 * InnerTube search filter (Interface Freeze). Maps to the base64 `params`
 * protobuf the `WEB_REMIX` client sends (Phase 1b `SearchParams`).
 *
 * The "local-scope" gate is consumer-side (SEC-F12): when the UI's active
 * scope is local-only, Phase 4's search feature must not call
 * [YouTubeClient.search]/[YouTubeClient.suggestions] at all — there is no LOCAL
 * member here because this enum describes YouTube search scopes only.
 */
public enum class SearchFilter {
    ALL,
    SONGS,
    ALBUMS,
    ARTISTS,
    VIDEOS,
    PLAYLISTS,
}
