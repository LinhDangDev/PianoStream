package com.pianostream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.youtube.api.YouTubeItem
import com.pianostream.core.youtube.spotify.SpotifyImportRunner
import com.pianostream.core.youtube.spotify.SpotifyPlaylistImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Import-from-Spotify flow owner: paste link → fetch the public embed
 * track list → match each track against YouTube Music search (bounded
 * parallel, cancel-able) → write the matches into a new local playlist.
 *
 * Egress gate (F12): `localOnlySearch=true` aborts before any network call —
 * the whole feature is a YouTube-search client.
 */
@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val importer: SpotifyPlaylistImporter,
    private val runner: SpotifyImportRunner,
    private val settings: SettingsDataStore,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {

    /** One row in the progress list. */
    data class ImportRow(
        val track: SpotifyPlaylistImporter.ImportedTrack,
        val status: Status = Status.PENDING,
        /** Matched YouTube art when settled; falls back to the track's own. */
        val artworkUri: String? = track.artworkUrl,
    ) {
        enum class Status { PENDING, MATCHED, NOT_FOUND, FAILED }
    }

    sealed interface Phase {
        /** Waiting for a pasted link. */
        data object Idle : Phase

        /** Fetching/parsing the Spotify embed page. */
        data object Fetching : Phase

        /** YouTube-matching in flight. */
        data class Matching(val rows: List<ImportRow>, val done: Int, val total: Int) : Phase

        /** Playlist written. [playlistId] opens the detail on confirm. */
        data class Done(
            val playlistId: Long,
            val playlistName: String,
            val imported: Int,
            val missed: Int,
        ) : Phase

        /** User-facing failure with a fixed message key (no raw URLs). */
        data class Error(val message: String) : Phase
    }

    data class UiState(
        val url: String = "",
        val phase: Phase = Phase.Idle,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var importJob: Job? = null

    fun onUrlChanged(url: String) {
        _state.update { it.copy(url = url) }
    }

    fun start() {
        if (importJob?.isActive == true) return
        val url = _state.value.url
        importJob = viewModelScope.launch { runImport(url) }
    }

    /** Cancels an in-flight fetch/match and returns to the paste field. */
    fun cancel() {
        importJob?.cancel()
        importJob = null
        _state.update { it.copy(phase = Phase.Idle) }
    }

    private suspend fun runImport(url: String) {
        // F12 — zero YouTube egress while local-only is on.
        if (runCatching { settings.localOnlySearch.first() }.getOrDefault(false)) {
            _state.update {
                it.copy(
                    phase = Phase.Error(
                        "Import needs YouTube search — turn off " +
                            "\"Local-only search\" in Settings first.",
                    ),
                )
            }
            return
        }
        _state.update { it.copy(phase = Phase.Fetching) }
        val playlist = try {
            importer.import(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyPlaylistImporter.SpotifyImportException) {
            _state.update { it.copy(phase = Phase.Error(messageFor(e.kind))) }
            return
        } catch (e: Exception) {
            _state.update {
                it.copy(phase = Phase.Error("Couldn't fetch that playlist."))
            }
            return
        }
        if (playlist.tracks.isEmpty()) {
            _state.update {
                it.copy(phase = Phase.Error("That playlist looks empty."))
            }
            return
        }

        val rows = playlist.tracks.map { ImportRow(it) }.toMutableList()
        _state.update {
            it.copy(phase = Phase.Matching(rows = rows.toList(), done = 0, total = rows.size))
        }

        val results = try {
            runner.run(playlist.tracks) { index, match ->
                rows[index] = rows[index].copy(
                    status = when (match) {
                        is SpotifyImportRunner.Match.Matched -> ImportRow.Status.MATCHED
                        is SpotifyImportRunner.Match.NotFound -> ImportRow.Status.NOT_FOUND
                        is SpotifyImportRunner.Match.Failed -> ImportRow.Status.FAILED
                    },
                    artworkUri = (match as? SpotifyImportRunner.Match.Matched)
                        ?.item?.artworkUri
                        ?: rows[index].artworkUri,
                )
                val done = rows.count { it.status != ImportRow.Status.PENDING }
                _state.update {
                    it.copy(phase = Phase.Matching(rows.toList(), done, rows.size))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(phase = Phase.Error("Import failed while matching tracks."))
            }
            return
        }

        val name = playlist.name.ifBlank { "Spotify import" }
        val playlistId = runCatching { playbackRepository.createPlaylist(name) }
            .getOrDefault(-1L)
        if (playlistId < 0) {
            _state.update {
                it.copy(phase = Phase.Error("Couldn't create the playlist."))
            }
            return
        }

        // Dense positions for the matched rows; failures are omitted from the
        // playlist entirely (they're still listed in the sheet's results).
        var position = 0
        val now = System.currentTimeMillis()
        results.forEachIndexed { i, match ->
            val item = (match as? SpotifyImportRunner.Match.Matched)?.item
                ?: return@forEachIndexed
            val videoId = item.videoId ?: return@forEachIndexed
            val track = playlist.tracks[i]
            runCatching {
                playbackRepository.addToPlaylist(
                    playlistId,
                    queueItemFor(position, track, item, now),
                )
            }
            position++
        }

        val missed = results.count { it !is SpotifyImportRunner.Match.Matched }
        _state.update {
            it.copy(
                phase = Phase.Done(
                    playlistId = playlistId,
                    playlistName = name,
                    imported = position,
                    missed = missed,
                ),
            )
        }
    }

    private fun queueItemFor(
        position: Int,
        track: SpotifyPlaylistImporter.ImportedTrack,
        item: YouTubeItem,
        addedAt: Long,
    ): QueueItem = QueueItem(
        position = position,
        mediaId = PianoStreamUri.stream(item.videoId!!),
        title = track.title,
        artist = track.artist,
        artworkUri = item.artworkUri ?: track.artworkUrl,
        source = MediaSource.YOUTUBE,
        durationMs = track.durationMs ?: item.durationMs,
        addedAt = addedAt,
    )

    private fun messageFor(
        kind: SpotifyPlaylistImporter.SpotifyImportException.Kind,
    ): String = when (kind) {
        SpotifyPlaylistImporter.SpotifyImportException.Kind.INVALID_URL ->
            "That doesn't look like a Spotify playlist link."
        SpotifyPlaylistImporter.SpotifyImportException.Kind.NOT_FOUND_OR_PRIVATE ->
            "Playlist not found — is it public?"
        SpotifyPlaylistImporter.SpotifyImportException.Kind.PARSE ->
            "Couldn't read Spotify's track list."
        SpotifyPlaylistImporter.SpotifyImportException.Kind.NETWORK ->
            "Spotify didn't answer — check the connection."
    }
}
