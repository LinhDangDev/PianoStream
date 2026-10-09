package com.pianostream.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.local.scan.LibraryHasher
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val youTubeClient: YouTubeClient,
    private val settings: SettingsDataStore,
    private val playbackRepository: PlaybackRepository,
    private val recentSearches: RecentSearchesStore,
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
        val typeFilter: TypeFilter = TypeFilter.ALL,
        /** Newest-first recent queries — local data, shown on an empty field. */
        val recentQueries: List<String> = emptyList(),
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
        // Re-run the pending query on any scope that shows the YouTube
        // section (ALL included) — otherwise the section stays empty until
        // the user retypes, which reads as broken.
        refresh(_state.value.query)
    }

    fun setScope(scope: Scope) {
        _state.update { it.copy(scope = scope) }
        refresh(_state.value.query)
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

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300) // debounce — one query burst per idle pause
            runQuery(query)
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
                    localHits = emptyList(),
                    youtubeItems = emptyList(),
                    suggestions = emptyList(),
                    searching = false,
                )
            }
            return
        }
        _state.update { it.copy(searching = true) }
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
                        is YoutubeLink.Playlist ->
                            youTubeClient.browse("VL${link.id}")
                        else ->
                            // Server resolves pasted URLs and bare ids alike
                            // (verified: youtu.be/<id> returns the video).
                            youTubeClient.search(query, SearchFilter.ALL)
                    }
                }.onSuccess { page ->
                    _state.update {
                        // Dedupe — link searches echo the same videoId many
                        // times and LazyColumn keys must be unique.
                        it.copy(
                            youtubeItems = page.sections
                                .flatMap { s2 -> s2.items }
                                .distinctBy { item -> item.videoId ?: item.title },
                            youtubeError = false,
                        )
                    }
                    if (link != null) {
                        val resolved = page.title
                            ?: page.sections.firstOrNull()?.items?.firstOrNull()?.title
                        recentSearches.record(resolved ?: query)
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

    private sealed interface YoutubeLink {
        data class Video(val id: String) : YoutubeLink
        data class Playlist(val id: String) : YoutubeLink
    }

    private companion object {
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
