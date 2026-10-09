package com.pianostream.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.local.scan.LibraryHasher
import com.pianostream.core.media.downloads.DownloadManager
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search egress gate (F12/F16):
 *  - raw input is forwarded verbatim to `LibraryRepository.search` — the UI
 *    never constructs FTS syntax; `LibrarySearchDao` is the sole MATCH
 *    builder with a `LIKE` fallback.
 *  - `YouTubeClient.search` only runs while the YouTube scope is enabled
 *    (`localOnlySearch=false`) — `localOnlySearch=true` ⇒ zero calls.
 *  - `get_search_suggestions` only while `sendSearchSuggestions=true` AND a
 *    YouTube scope chip is active — never on a local-only scope.
 *  - the YouTube section renders only after the per-session disclosure
 *    opt-in ([acceptYoutubeDisclosure]).
 *
 * Submit-only model: [onQueryChanged] edits text state only — no backend is
 * touched per keystroke. [submit] (IME Search/Enter, suggestion chip, or a
 * recent-searches row) is the single entry point that runs the query and
 * writes recents. [clearQuery] wipes text + results instantly.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val youTubeClient: YouTubeClient,
    private val settings: SettingsDataStore,
    private val playbackRepository: PlaybackRepository,
    private val recentSearches: RecentSearchesStore,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    enum class Scope { ALL, LOCAL, YOUTUBE }

    /** Second chip row — filters rendered sections/rows by result kind. */
    enum class TypeFilter { ALL, SONGS, VIDEOS, ARTISTS, ALBUMS, PLAYLISTS }

    data class ResultRow(
        val mediaId: String?,
        val title: String,
        val subtitle: String?,
        val artworkUri: String?,
        val durationMs: Long?,
        val local: Boolean,
        val error: String?,
    )

    data class UiState(
        val query: String = "",
        val scope: Scope = Scope.ALL,
        val localHits: List<SearchHit> = emptyList(),
        val youtubeItems: List<YouTubeItem> = emptyList(),
        val suggestions: List<String> = emptyList(),
        val searching: Boolean = false,
        val youtubeOptedIn: Boolean = false,
        val youtubeEnabled: Boolean = false,
        val youtubeError: Boolean = false,
        val suggestionsEnabled: Boolean = false,
        val online: Boolean = true,
        /** mediaIds currently saved to the collection — heart state per row. */
        val favoriteIds: Set<String> = emptySet(),
        /** videoId → download record — the row icon's state machine. */
        val downloads: Map<String, DownloadItem> = emptyMap(),
        val typeFilter: TypeFilter = TypeFilter.ALL,
        /** Newest-first recent queries — local data, shown on an empty field. */
        val recentQueries: List<String> = emptyList(),
        /** Last query actually executed — results below belong to it. */
        val submittedQuery: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            settings.localOnlySearch.collect { localOnly ->
                _state.update { it.copy(youtubeEnabled = !localOnly) }
            }
        }
        viewModelScope.launch {
            settings.sendSearchSuggestions.collect { on ->
                _state.update { it.copy(suggestionsEnabled = on) }
            }
        }
        viewModelScope.launch {
            playbackRepository.observeFavorites().collect { favs ->
                _state.update { it.copy(favoriteIds = favs.mapTo(HashSet()) { f -> f.mediaId }) }
            }
        }
        viewModelScope.launch {
            recentSearches.recent.collect { list ->
                _state.update { it.copy(recentQueries = list) }
            }
        }
        viewModelScope.launch {
            downloadManager.downloads.collect { map ->
                _state.update { it.copy(downloads = map) }
            }
        }
    }

    /** Download icon on a YouTube row — enqueue or retry (failed rows). */
    fun enqueueDownload(videoId: String, title: String, artist: String?, artworkUri: String?) {
        downloadManager.enqueue(videoId, title, artist, artworkUri)
    }

    /** Removes the download (also cancels an in-flight one). */
    fun removeDownload(videoId: String) {
        viewModelScope.launch { downloadManager.remove(videoId) }
    }

    fun setTypeFilter(filter: TypeFilter) {
        _state.update { it.copy(typeFilter = filter) }
    }

    /** Trailing ✕ on a recent-searches row — drops just that entry. */
    fun removeRecent(query: String) {
        viewModelScope.launch { recentSearches.remove(query) }
    }

    /** "Clear all" on the recent-searches section header. */
    fun clearRecents() {
        viewModelScope.launch { recentSearches.clear() }
    }

    /**
     * Local hit → `pianostream://local/<contentKey>` mediaId (CC2): the cue
     * ordinal is the hit's index among its disc's tracks ordered by
     * `trackNo` (`tracksOf` order == scan insertion order).
     */
    suspend fun mediaIdFor(hit: SearchHit): String? {
        val disc = libraryRepository.discById(hit.discId) ?: return null
        val idx = libraryRepository.tracksOf(disc.id).indexOfFirst { it.id == hit.trackId }
        return if (idx >= 0) LibraryHasher.mediaId(disc.folderPath, idx) else null
    }

    /** First-run disclosure accepted for this session (F12). */
    fun acceptYoutubeDisclosure() {
        _state.update { it.copy(youtubeOptedIn = true) }
        // Re-run the last submitted query on any scope that shows the
        // YouTube section (ALL included) — otherwise the section stays
        // empty until the user resubmits, which reads as broken.
        refresh(_state.value.submittedQuery)
    }

    fun setScope(scope: Scope) {
        _state.update { it.copy(scope = scope) }
        // Re-running the submitted query under the new scope is an explicit
        // user action — never the in-flight text.
        refresh(_state.value.submittedQuery)
    }

    /**
     * Toggle a row in/out of the collection (Favorites tab). [mediaId] must
     * be the same `pianostream://` id the row queues with so isFavorite stays
     * consistent between search rows, the player heart and the tab.
     */
    fun toggleFavorite(
        mediaId: String,
        title: String,
        artist: String?,
        artworkUri: String?,
        source: MediaSource,
    ) {
        viewModelScope.launch {
            if (mediaId in _state.value.favoriteIds) {
                playbackRepository.removeFavorite(mediaId)
            } else {
                playbackRepository.addFavorite(
                    FavoriteItem(
                        mediaId = mediaId,
                        title = title,
                        artist = artist,
                        artworkUri = artworkUri,
                        source = source,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /**
     * Text edits only. No backend call, no recents write — the query runs
     * exclusively on [submit].
     */
    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** IME Search/Enter — runs the field's current text. */
    fun submit() = submit(_state.value.query)

    /**
     * Runs [text] (a suggestion chip or recent-searches row taps in their
     * own query) — sets the field text then executes it once.
     */
    fun submit(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runQuery(text) }
    }

    /** Trailing ✕ — clears text and results instantly, no network. */
    fun clearQuery() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                query = "",
                submittedQuery = "",
                localHits = emptyList(),
                youtubeItems = emptyList(),
                suggestions = emptyList(),
                searching = false,
                youtubeError = false,
            )
        }
    }

    private fun refresh(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runQuery(query) }
    }

    private suspend fun runQuery(query: String) {
        val s = _state.value
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    submittedQuery = "",
                    localHits = emptyList(),
                    youtubeItems = emptyList(),
                    suggestions = emptyList(),
                    searching = false,
                )
            }
            return
        }
        _state.update { it.copy(searching = true, submittedQuery = query) }
        try {
            if (s.scope != Scope.YOUTUBE) {
                // verbatim — LibrarySearchDao owns FTS syntax (F16)
                val hits = libraryRepository.search(query).first()
                _state.update { it.copy(localHits = hits) }
            } else {
                _state.update { it.copy(localHits = emptyList()) }
            }

            // F12 — YouTube egress only while opted in AND the scope allows
            // it; read the consent flags via first() so the gate is never a
            // collector-timing race (localOnlySearch=true ⇒ zero calls).
            val localOnly = settings.localOnlySearch.first()
            val suggestionsOn = settings.sendSearchSuggestions.first()
            // A pasted YouTube link is affirmative YouTube intent: treat it
            // as the session opt-in so links "just work".
            val link = extractYoutubeLink(query)
            val optedIn = s.youtubeOptedIn || link != null
            if (link != null) _state.update { it.copy(youtubeOptedIn = true) }
            val canYoutube = !localOnly && optedIn && s.scope != Scope.LOCAL
            // Record on submit. A pasted link is recorded after resolution
            // under the resolved title; everywhere else the raw input stands.
            if (link == null || !canYoutube) {
                recentSearches.record(query)
            }
            if (canYoutube) {
                runCatching {
                    when (link) {
                        is YoutubeLink.Playlist -> loadPlaylist(link.id)
                        else ->
                            // Server resolves pasted URLs and bare ids alike
                            // (verified: youtu.be/<id> returns the video).
                            youTubeClient.search(query, SearchFilter.ALL)
                                .let { p ->
                                    p.title to p.sections.flatMap { s2 -> s2.items }
                                }
                    }
                }.onSuccess { (title, items) ->
                    _state.update {
                        // Dedupe — link searches echo the same videoId many
                        // times and LazyColumn keys must be unique.
                        it.copy(
                            youtubeItems = items
                                .distinctBy { item -> item.videoId ?: item.title },
                            youtubeError = false,
                        )
                    }
                    if (link != null) {
                        recentSearches.record(title ?: items.firstOrNull()?.title ?: query)
                    }
                }.onFailure {
                    // Silent failure read as "No results" — surface a flag so
                    // the section can say the call failed, not that YouTube
                    // has nothing. ExtractionException messages are redacted.
                    android.util.Log.w(
                        "SearchViewModel",
                        "YouTube search failed: ${it.javaClass.simpleName}: ${it.message}",
                    )
                    _state.update { s2 -> s2.copy(youtubeItems = emptyList(), youtubeError = true) }
                    if (link != null) recentSearches.record(query)
                }
                if (suggestionsOn && s.scope == Scope.YOUTUBE) {
                    runCatching { youTubeClient.suggestions(query) }
                        .onSuccess { list -> _state.update { it.copy(suggestions = list) } }
                }
            } else {
                _state.update {
                    it.copy(
                        youtubeItems = emptyList(),
                        suggestions = emptyList(),
                        youtubeError = false,
                    )
                }
            }
        } finally {
            _state.update { it.copy(searching = false) }
        }
    }

    /**
     * PLAYLIST-kind row → "Play all": expands the playlist into its tracks
     * (the same [loadPlaylist] a pasted `list=` link uses). Returns items
     * with videoIds ready for `QueueItem` mapping; empty on failure so the
     * caller can toast instead of queueing a dead page.
     */
    suspend fun playlistTracks(browseId: String?): List<YouTubeItem> {
        val id = browseId?.removePrefix("VL")?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return runCatching { loadPlaylist(id).second }
            .onFailure {
                runCatching {
                    android.util.Log.w(
                        "SearchViewModel",
                        "Playlist expand failed: ${it.javaClass.simpleName}",
                    )
                }
            }
            .getOrDefault(emptyList())
    }

    /**
     * `browse("VL<list>")` + continuation pages: playlists over one page
     * (≈100 tracks) keep coming as bare `continuationItems`. Bounded so a
     * pathological playlist can't page forever.
     */
    private suspend fun loadPlaylist(playlistId: String): Pair<String?, List<YouTubeItem>> {
        val items = ArrayList<YouTubeItem>()
        var page = youTubeClient.browse("VL$playlistId")
        val title = page.title
        page.sections.forEach { items += it.items }
        var continuation = page.continuation
        var pages = 1
        while (continuation != null &&
            pages < MAX_PLAYLIST_PAGES &&
            items.size < MAX_PLAYLIST_TRACKS
        ) {
            page = youTubeClient.browse("VL$playlistId", continuation)
            page.sections.forEach { items += it.items }
            continuation = page.continuation
            pages++
        }
        return title to items
    }

    private sealed interface YoutubeLink {
        data class Video(val id: String) : YoutubeLink
        data class Playlist(val id: String) : YoutubeLink
    }

    private companion object {
        /** Playlist expansion bounds — pages, then absolute track count. */
        const val MAX_PLAYLIST_PAGES = 5
        const val MAX_PLAYLIST_TRACKS = 500
        val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        val WATCH_URL = Regex("(?:[?&]v=|youtu\\.be/|shorts/|/watch/|embed/)([A-Za-z0-9_-]{11})")
        val LIST_URL = Regex("[?&]list=([A-Za-z0-9_-]+)")

        /** YouTube link or bare 11-char video id pasted into the box. */
        fun extractYoutubeLink(query: String): YoutubeLink? {
            val q = query.trim()
            if (q.contains("youtube.com") || q.contains("youtu.be")) {
                LIST_URL.find(q)?.let { return YoutubeLink.Playlist(it.groupValues[1]) }
            }
            WATCH_URL.find(q)?.let { return YoutubeLink.Video(it.groupValues[1]) }
            return if (VIDEO_ID.matches(q)) YoutubeLink.Video(q) else null
        }
    }
}
