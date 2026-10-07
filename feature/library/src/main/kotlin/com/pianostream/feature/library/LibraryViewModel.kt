package com.pianostream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Library surface (F8/M2/E2/B1): the SAF tree grant lives in
 * `libraryRootTreeUri`; scans report progress and never silently wipe —
 * a result with fewer discs than before demands explicit confirmation
 * before deletion commits ([confirmShrink]); `missing` tombstones on
 * favourites surface as the "N favourites no longer in your library"
 * banner (B1/R9).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackRepository: PlaybackRepository,
    private val settings: SettingsDataStore,
) : ViewModel() {

    data class ScanSummary(
        val newDiscs: Int,
        val removedDiscs: Int,
        val tracks: Int,
        val warnings: List<String>,
    )

    data class UiState(
        val discs: List<Disc> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val composers: List<String> = emptyList(),
        val queue: List<QueueItem> = emptyList(),
        val favorites: List<FavoriteItem> = emptyList(),
        val missingFavorites: Int = 0,
        val hasRoot: Boolean = false,
        val rootPath: String? = null,
        val scanning: Boolean = false,
        val progress: ScanProgress? = null,
        val lastScan: ScanSummary? = null,
        val pendingShrink: ScanResult? = null,
        val selectedDisc: Disc? = null,
        val grantError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var previousDiscCount: Int? = null
    private var tracksJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            combine(
                libraryRepository.discs,
                playbackRepository.observeFavorites(),
                playbackRepository.observeQueue(),
                settings.libraryRootTreeUri,
            ) { discs, favs, queue, root ->
                _state.update {
                    it.copy(
                        discs = discs,
                        favorites = favs,
                        missingFavorites = favs.count { f -> f.missing },
                        queue = queue,
                        hasRoot = !root.isNullOrBlank(),
                        rootPath = root?.let(::displayPath),
                    )
                }
            }.collect {}
        }
        viewModelScope.launch {
            libraryRepository.scanProgress.collect { p ->
                // A null currentFolder is the driver's completion marker —
                // scanning mirrors "work in flight", not "a scan happened".
                _state.update {
                    it.copy(progress = p, scanning = p.currentFolder != null)
                }
            }
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    composers = runCatching {
                        libraryRepository.distinctComposers()
                    }.getOrDefault(emptyList()),
                )
            }
        }
    }

    fun selectDisc(disc: Disc?) {
        tracksJob?.cancel()
        _state.update { it.copy(selectedDisc = disc, tracks = emptyList()) }
        if (disc != null) {
            tracksJob = viewModelScope.launch {
                libraryRepository.observeTracks(disc.id).collect { tracks ->
                    _state.update { it.copy(tracks = tracks) }
                }
            }
        }
    }

    /** Called when the user picks a SAF tree; returns the error to show (F8). */
    suspend fun onTreePicked(result: PickerResult) {
        when (result) {
            is PickerResult.Granted -> {
                // Persist the granted tree — SAF grant itself was already
                // taken by SafPermissions.takePersisted (F8).
                settings.setLibraryRootTreeUri(result.treeUri)
                _state.update { it.copy(grantError = null) }
                rescan()
            }
            is PickerResult.UnsupportedProvider ->
                _state.update {
                    it.copy(grantError = "Pick a local folder — that provider isn't supported.")
                }
            PickerResult.Cancelled -> Unit
        }
    }

    fun rescan() {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, pendingShrink = null) }
            val prev = previousDiscCount ?: _state.value.discs.size.takeIf { it > 0 }
            val result = runCatching { libraryRepository.startScan() }.getOrNull()
            if (result == null) {
                _state.update { it.copy(scanning = false) }
                return@launch
            }
            if (result.grantLost) {
                _state.update {
                    it.copy(
                        scanning = false,
                        grantError = "Library access was lost — grant the folder again.",
                    )
                }
                return@launch
            }
            val prevCount = prev ?: 0
            if (result.discCount < prevCount && result.discCount > 0) {
                // M2 — rows are left untouched until the user confirms.
                _state.update {
                    it.copy(scanning = false, pendingShrink = result)
                }
                return@launch
            }
            previousDiscCount = result.discCount
            _state.update {
                it.copy(
                    scanning = false,
                    lastScan = ScanSummary(
                        newDiscs = (result.discCount - prevCount).coerceAtLeast(0),
                        removedDiscs = (prevCount - result.discCount).coerceAtLeast(0),
                        tracks = result.trackCount,
                        warnings = result.warnings,
                    ),
                )
            }
        }
    }

    /**
     * M2 — user accepted the smaller library. Rows were left untouched
     * (the driver never deletes while `found < previous`); the commit is
     * a wipe + fresh scan, which keeps contentKey-derived mediaIds intact
     * (CC2 — keys are path hashes, not rowids).
     */
    fun confirmShrink() {
        viewModelScope.launch {
            val pending = _state.value.pendingShrink ?: return@launch
            _state.update { it.copy(pendingShrink = null, scanning = true) }
            libraryRepository.clearAll()
            previousDiscCount = null
            val result = runCatching { libraryRepository.startScan() }.getOrNull()
            previousDiscCount = result?.discCount ?: pending.discCount
            _state.update {
                it.copy(
                    scanning = false,
                    lastScan = ScanSummary(
                        newDiscs = 0,
                        removedDiscs = pending.discCount.let { d ->
                            ((_state.value.discs.size - d)).coerceAtLeast(0)
                        },
                        tracks = result?.trackCount ?: pending.trackCount,
                        warnings = result?.warnings ?: pending.warnings,
                    ),
                )
            }
        }
    }

    fun dismissShrink() {
        _state.update { it.copy(pendingShrink = null) }
    }

    fun revokeAccess() {
        viewModelScope.launch {
            libraryRepository.revokeLibraryAccess()
            _state.update { it.copy(hasRoot = false, rootPath = null, discs = emptyList()) }
        }
    }

    private fun displayPath(treeUri: String): String {
        val docId = treeUri.substringAfterLast(':')
        return docId.ifBlank { "Storage root" }
    }

    /** Result of the SAF picker surface — mirrors SafPermissions.TakeResult. */
    sealed interface PickerResult {
        data class Granted(val treeUri: String) : PickerResult
        data object UnsupportedProvider : PickerResult
        data object Cancelled : PickerResult
    }
}
