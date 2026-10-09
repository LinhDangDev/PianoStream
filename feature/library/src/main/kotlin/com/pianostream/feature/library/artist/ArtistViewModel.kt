package com.pianostream.feature.library.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.ArtistTrack
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.local.scan.LibraryHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Artist detail page (local-only): discs credited to the artist plus every
 * track they perform or composed. Queue rows reuse
 * `LibraryViewModel.discQueue`'s exact mapping — mediaIds are
 * `pianostream://local/<contentKey>` hashing `(folderPath, indexInCue)`
 * (CC2); the join in `LibraryRepository.observeArtistTracks` supplies both
 * halves so no second lookup is needed per row.
 */
@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    data class ArtistUiState(
        val artistName: String = "",
        val discs: List<Disc> = emptyList(),
        /** Flat track list (spec shape) — same order as [trackRows]/[queue]. */
        val tracks: List<Track> = emptyList(),
        /** Joined rows (track + disc context) parallel to [tracks]/[queue]. */
        val trackRows: List<ArtistTrack> = emptyList(),
        val totalDurationMs: Long = 0L,
        /** Cue-ordinal queue rows parallel to [tracks] — play/shuffle/tap. */
        val queue: List<QueueItem> = emptyList(),
        /** False until the first repo emission — gates the empty state. */
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var collectJob: Job? = null

    /** Binds the page to [artist] — idempotent for a repeated name. */
    fun load(artist: String) {
        if (artist == _state.value.artistName && collectJob != null) return
        collectJob?.cancel()
        _state.value = ArtistUiState(artistName = artist)
        collectJob = viewModelScope.launch {
            combine(
                libraryRepository.observeArtistDiscs(artist),
                libraryRepository.observeArtistTracks(artist),
            ) { discs, rows -> discs to rows }.collect { (discs, rows) ->
                val now = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        discs = discs,
                        trackRows = rows,
                        tracks = rows.map { r -> r.track },
                        totalDurationMs = rows.sumOf { r ->
                            r.track.endMs?.let { e -> e - r.track.startMs } ?: 0L
                        },
                        queue = rows.mapIndexed { i, r -> r.toQueueItem(i, now) },
                        loaded = true,
                    )
                }
            }
        }
    }

    /** Ordered queue starting at [index] — a Tracks-section row tap (CC1). */
    fun queueFrom(index: Int): Pair<List<QueueItem>, Int> =
        _state.value.queue to index.coerceIn(
            0,
            (_state.value.queue.size - 1).coerceAtLeast(0),
        )

    /** "Play" — the artist's tracks in order from the top. */
    fun playAll(): Pair<List<QueueItem>, Int> = _state.value.queue to 0

    /**
     * "Shuffle" — a shuffled copy; positions are re-seated because
     * `QueueAssembler` sorts rows by `position` (keeping the originals
     * would silently restore library order).
     */
    fun shuffleAll(): Pair<List<QueueItem>, Int> =
        _state.value.queue.shuffled().mapIndexed { i, q -> q.copy(position = i) } to 0

    /** Same QueueItem mapping as `LibraryViewModel.discQueue` (CC1/CC2). */
    private fun ArtistTrack.toQueueItem(position: Int, now: Long) = QueueItem(
        position = position,
        mediaId = LibraryHasher.mediaId(discFolderPath, indexInCue),
        title = track.title,
        artist = track.performer ?: track.composer,
        artworkUri = discArtworkUri,
        source = MediaSource.LOCAL,
        durationMs = track.endMs?.let { it - track.startMs },
        addedAt = now,
    )
}
