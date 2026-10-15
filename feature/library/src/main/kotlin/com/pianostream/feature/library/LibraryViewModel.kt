package com.pianostream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.Playlist
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.settings.LibraryPrefsStore
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.local.scan.LibraryHasher
import com.pianostream.core.media.downloads.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val libraryPrefs: LibraryPrefsStore,
    private val downloadManager: DownloadManager,
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
        val playlists: List<Playlist> = emptyList(),
        /** Playlist opened in the Playlists tab's detail view, null = list. */
        val selectedPlaylistId: Long? = null,
        val playlistTracks: List<QueueItem> = emptyList(),
        val hasRoot: Boolean = false,
        val rootPath: String? = null,
        val scanning: Boolean = false,
        val progress: ScanProgress? = null,
        val lastScan: ScanSummary? = null,
        val pendingShrink: ScanResult? = null,
        val selectedDisc: Disc? = null,
        val grantError: String? = null,
        /** "Search library" field — live-filters the visible tab's rows. */
        val libraryQuery: String = "",
        /** Offline downloads, newest first — the Downloads tab's rows. */
        val downloads: List<DownloadItem> = emptyList(),
        val discsSort: LibraryPrefsStore.DiscsSort = LibraryPrefsStore.DiscsSort.RECENT,
        val discsView: LibraryPrefsStore.DiscsView = LibraryPrefsStore.DiscsView.LIST,
        /** Result line of the last "Merge into…" — sits above the list. */
        val mergeNotice: MergeNotice? = null,
    )

    /** "Merged 'A' into 'B'" outcome + the source id for the delete follow-up. */
    data class MergeNotice(
        val sourceId: Long,
        val sourceName: String,
        val targetName: String,
        val added: Int,
        val skipped: Int,
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
                playbackRepository.observePlaylists(),
            ) { discs, favs, queue, root, playlists ->
                _state.update {
                    it.copy(
                        discs = discs,
                        favorites = favs,
                        missingFavorites = favs.count { f -> f.missing },
                        queue = queue,
                        playlists = playlists,
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
        viewModelScope.launch {
            libraryPrefs.discsSort.collect { sort ->
                _state.update { it.copy(discsSort = sort) }
            }
        }
        viewModelScope.launch {
            libraryPrefs.discsView.collect { view ->
                _state.update { it.copy(discsView = view) }
            }
        }
        viewModelScope.launch {
            downloadManager.downloads.collect { map ->
                _state.update {
                    it.copy(
                        downloads = map.values.sortedByDescending(DownloadItem::createdAt),
                    )
                }
            }
        }
    }

    // --- find in library ------------------------------------------------------

    /** Backs the pinned "Search library" field (empty = unfiltered). */
    fun setLibraryQuery(query: String) {
        _state.update { it.copy(libraryQuery = query) }
    }

    // --- discs sort / view ----------------------------------------------------

    fun setDiscsSort(sort: LibraryPrefsStore.DiscsSort) {
        viewModelScope.launch { libraryPrefs.setDiscsSort(sort) }
    }

    /** list <-> grid flip; persisted so the next visit keeps the choice. */
    fun toggleDiscsView() {
        val next = when (_state.value.discsView) {
            LibraryPrefsStore.DiscsView.LIST -> LibraryPrefsStore.DiscsView.GRID
            LibraryPrefsStore.DiscsView.GRID -> LibraryPrefsStore.DiscsView.LIST
        }
        viewModelScope.launch { libraryPrefs.setDiscsView(next) }
    }

    /**
     * Queue rows for the selected disc ordered by cue position (tap a track →
     * play the disc from that track, CC1). mediaIds are
     * `pianostream://local/<contentKey>` where the key hashes
     * `(folderPath, cueOrdinal)` (CC2/B3); cue ordinal == the track's index
     * in `tracks` (scan writes rows in cue order).
     */
    fun discQueue(startIndex: Int): Pair<List<QueueItem>, Int>? {
        val s = _state.value
        val disc = s.selectedDisc ?: return null
        if (s.tracks.isEmpty() || startIndex !in s.tracks.indices) return null
        val now = System.currentTimeMillis()
        val rows = s.tracks.mapIndexed { i, t ->
            QueueItem(
                position = i,
                mediaId = LibraryHasher.mediaId(disc.folderPath, i),
                title = t.title,
                artist = t.performer ?: t.composer,
                artworkUri = disc.artworkUri,
                source = MediaSource.LOCAL,
                durationMs = t.endMs?.let { it - t.startMs },
                addedAt = now,
            )
        }
        return rows to startIndex
    }

    /**
     * One-shot play requests emitted when a tap should start playback
     * directly — tapping a single (a one-track disc) plays it instead of
     * silently selecting it (the old behaviour looked like the tap did
     * nothing: the track list only shows under the Tracks tab).
     */
    private val _playRequests =
        MutableSharedFlow<Pair<List<QueueItem>, Int>>(extraBufferCapacity = 1)
    val playRequests: SharedFlow<Pair<List<QueueItem>, Int>> = _playRequests

    fun selectDisc(disc: Disc?) {
        tracksJob?.cancel()
        _state.update { it.copy(selectedDisc = disc, tracks = emptyList()) }
        if (disc != null) {
            var pendingAutoPlay = disc.isSingle()
            tracksJob = viewModelScope.launch {
                libraryRepository.observeTracks(disc.id).collect { tracks ->
                    _state.update { it.copy(tracks = tracks) }
                    if (pendingAutoPlay && tracks.isNotEmpty()) {
                        pendingAutoPlay = false
                        discQueue(0)?.let { _playRequests.tryEmit(it) }
                    }
                }
            }
        }
    }

    // --- user playlists -----------------------------------------------------

    private var playlistTracksJob: kotlinx.coroutines.Job? = null

    /** Opens a playlist's detail view (null backs out to the list). */
    fun selectPlaylist(id: Long?) {
        playlistTracksJob?.cancel()
        _state.update { it.copy(selectedPlaylistId = id, playlistTracks = emptyList()) }
        if (id != null) {
            playlistTracksJob = viewModelScope.launch {
                playbackRepository.observePlaylistTracks(id).collect { tracks ->
                    _state.update { it.copy(playlistTracks = tracks) }
                }
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playbackRepository.createPlaylist(name) }
    }

    fun renameSelectedPlaylist(name: String) {
        val id = _state.value.selectedPlaylistId ?: return
        if (name.isBlank()) return
        viewModelScope.launch { playbackRepository.renamePlaylist(id, name) }
    }

    /** Edit-details write (name + description) on the open playlist. */
    fun updateSelectedPlaylistDetails(name: String, description: String) {
        val id = _state.value.selectedPlaylistId ?: return
        if (name.isBlank()) return
        viewModelScope.launch { playbackRepository.updatePlaylistDetails(id, name, description) }
    }

    /** Drag-to-reorder inside the open playlist's detail rows. */
    fun moveSelectedPlaylistItem(fromPosition: Int, toPosition: Int) {
        val id = _state.value.selectedPlaylistId ?: return
        viewModelScope.launch { playbackRepository.movePlaylistItem(id, fromPosition, toPosition) }
    }

    /** Deletes the selected playlist and backs out to the list. */
    fun deleteSelectedPlaylist() {
        val id = _state.value.selectedPlaylistId ?: return
        selectPlaylist(null)
        viewModelScope.launch { playbackRepository.deletePlaylist(id) }
    }

    fun addToPlaylist(playlistId: Long, item: QueueItem) {
        viewModelScope.launch { playbackRepository.addToPlaylist(playlistId, item) }
    }

    /** "New playlist" inside the add-to-playlist sheet: create then add. */
    fun createPlaylistAndAdd(name: String, item: QueueItem) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = playbackRepository.createPlaylist(name)
            if (id >= 0) playbackRepository.addToPlaylist(id, item)
        }
    }

    /**
     * Queue rows for [disc] (any disc — not just the selected one) built
     * the same way [discQueue] builds them: contentKey mediaIds in cue
     * order. Used by the disc ⋯ sheet's Play/Shuffle/Add-to-queue actions.
     */
    fun queueForDisc(disc: Disc, onReady: (List<QueueItem>) -> Unit) {
        viewModelScope.launch {
            val tracks = runCatching {
                libraryRepository.tracksOf(disc.id)
            }.getOrDefault(emptyList())
            if (tracks.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            onReady(
                tracks.mapIndexed { i, t ->
                    QueueItem(
                        position = i,
                        mediaId = LibraryHasher.mediaId(disc.folderPath, i),
                        title = t.title,
                        artist = t.performer ?: t.composer,
                        artworkUri = disc.artworkUri,
                        source = MediaSource.LOCAL,
                        durationMs = t.endMs?.let { it - t.startMs },
                        addedAt = now,
                    )
                },
            )
        }
    }

    /** Removes [mediaId] from the playlist open in the detail view. */
    fun removeFromSelectedPlaylist(mediaId: String) {
        val id = _state.value.selectedPlaylistId ?: return
        viewModelScope.launch { playbackRepository.removeFromPlaylist(id, mediaId) }
    }

    /** Removes [mediaId] from [playlistId] (add-to-playlist "Saved in" tap). */
    fun removeFromPlaylist(playlistId: Long, mediaId: String) {
        viewModelScope.launch { playbackRepository.removeFromPlaylist(playlistId, mediaId) }
    }

    /**
     * "Merge into…" on a playlist row — appends the source's tracks to the
     * target's tail (mediaId dupes skipped, order preserved); the source
     * playlist stays — deleting it is the notice's separate follow-up.
     */
    fun mergePlaylists(sourceId: Long, targetId: Long) {
        val s = _state.value
        val source = s.playlists.firstOrNull { it.id == sourceId } ?: return
        val target = s.playlists.firstOrNull { it.id == targetId } ?: return
        viewModelScope.launch {
            val result = playbackRepository.mergePlaylist(sourceId, targetId)
            _state.update {
                it.copy(
                    mergeNotice = MergeNotice(
                        sourceId = sourceId,
                        sourceName = source.name,
                        targetName = target.name,
                        added = result.added,
                        skipped = result.skipped,
                    ),
                )
            }
        }
    }

    /** "Also delete 'A'?" on the merge notice — removes the merge's source. */
    fun deleteMergeSource() {
        val sourceId = _state.value.mergeNotice?.sourceId ?: return
        _state.update { it.copy(mergeNotice = null) }
        viewModelScope.launch { playbackRepository.deletePlaylist(sourceId) }
    }

    fun dismissMergeNotice() {
        _state.update { it.copy(mergeNotice = null) }
    }

    /** Live membership for the add-to-playlist sheet's "Saved in" section. */
    fun playlistsContaining(mediaId: String): Flow<List<Long>> =
        playbackRepository.observePlaylistsContaining(mediaId)

    /** "Clear all" — drops [mediaId] from every playlist it sits in. */
    fun removeFromAllPlaylists(mediaId: String) {
        viewModelScope.launch { playbackRepository.removeFromAllPlaylists(mediaId) }
    }

    /**
     * QueueItem for one row of the selected disc — same mediaId derivation
     * as [discQueue] (CC2); used by the add-to-playlist sheet.
     */
    fun trackQueueItem(index: Int): QueueItem? =
        discQueue(index)?.first?.getOrNull(index)

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

    // --- downloads ------------------------------------------------------

    /** Row ⋯ → "Delete download" (also cancels an in-flight one). */
    fun removeDownload(videoId: String) {
        viewModelScope.launch { downloadManager.remove(videoId) }
    }

    /** Retries a FAILED download through the stored row's metadata. */
    fun retryDownload(videoId: String) {
        viewModelScope.launch { downloadManager.retry(videoId) }
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
