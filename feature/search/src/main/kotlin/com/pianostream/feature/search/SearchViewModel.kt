package com.pianostream.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.settings.SettingsDataStore
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
) : ViewModel() {

    enum class Scope { ALL, LOCAL, YOUTUBE }

    data class ResultRow(
        val mediaId: String,
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
        val suggestionsEnabled: Boolean = false,
        val online: Boolean = true,
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
        if (_state.value.scope == Scope.YOUTUBE) {
            refresh(_state.value.query)
        }
    }

    fun setScope(scope: Scope) {
        _state.update { it.copy(scope = scope) }
        refresh(_state.value.query)
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
            if (!localOnly && s.youtubeOptedIn && s.scope != Scope.LOCAL) {
                runCatching {
                    youTubeClient.search(query, SearchFilter.ALL)
                }.onSuccess { page ->
                    _state.update {
                        it.copy(youtubeItems = page.sections.flatMap { s2 -> s2.items })
                    }
                }.onFailure {
                    _state.update { it.copy(youtubeItems = emptyList()) }
                }
                if (suggestionsOn && s.scope == Scope.YOUTUBE) {
                    runCatching { youTubeClient.suggestions(query) }
                        .onSuccess { list -> _state.update { it.copy(suggestions = list) } }
                }
            } else {
                _state.update {
                    it.copy(youtubeItems = emptyList(), suggestions = emptyList())
                }
            }
        } finally {
            _state.update { it.copy(searching = false) }
        }
    }
}
