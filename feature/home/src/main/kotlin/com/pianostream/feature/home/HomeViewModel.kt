package com.pianostream.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeSection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home surface (C2): recently played + disc grid + composer chips
 * (track-level SONGWRITER aggregate — `GENRE` is constant in the corpus,
 * never a facet) + a "From YouTube" row gated on `localOnlySearch` (F12:
 * `YouTubeClient` is not called at all while local-only is on).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val libraryRepository: LibraryRepository,
    private val youTubeClient: YouTubeClient,
    private val settings: SettingsDataStore,
) : ViewModel() {

    data class UiState(
        val recentlyPlayed: List<PlayHistoryItem> = emptyList(),
        val discs: List<Disc> = emptyList(),
        val composers: List<String> = emptyList(),
        val youtubeSections: List<YouTubeSection> = emptyList(),
        val youtubeEnabled: Boolean = false,
        val youtubeError: String? = null,
    )

    private val composers = MutableStateFlow<List<String>>(emptyList())
    private val youtubeSections = MutableStateFlow<List<YouTubeSection>>(emptyList())
    private val youtubeError = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        playbackRepository.observeHistory(),
        libraryRepository.discs,
        composers,
        youtubeSections,
        settings.localOnlySearch,
    ) { history, discs, comp, yt, localOnly ->
        UiState(
            recentlyPlayed = history.take(10),
            discs = discs,
            composers = comp,
            youtubeSections = if (localOnly) emptyList() else yt,
            youtubeEnabled = !localOnly,
            youtubeError = if (localOnly) null else youtubeError.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            composers.value = runCatching { libraryRepository.distinctComposers() }
                .getOrDefault(emptyList())
        }
        viewModelScope.launch {
            // F12 — zero YouTube calls while local-only is on.
            if (!settings.localOnlySearch.first()) {
                runCatching { youTubeClient.browse("FEmusic_home") }
                    .onSuccess { youtubeSections.value = it.sections }
                    .onFailure { youtubeError.value = it.message }
            }
        }
    }
}
