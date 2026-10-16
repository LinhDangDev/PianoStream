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
 * Import-from-Spotify flow owner: paste link(s) → fetch each public embed
 * track list → match each track against YouTube Music search (bounded
 * parallel, cancel-able) → write the matches into one new local playlist
 * per link.
 *
 * Multi-link: the field takes several playlist links (one per line;
 * space/comma separated tolerated). Links run strictly sequentially —
 * per-link progress sits in [UiState.links]; a link that fails is marked
 * and the run moves on.
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

    /** One row in the per-track progress list. */
    data class ImportRow(
        val track: SpotifyPlaylistImporter.ImportedTrack,
        val status: Status = Status.PENDING,
        /** Matched YouTube art when settled; falls back to the track's own. */
        val artworkUri: String? = track.artworkUrl,
    ) {
        enum class Status { PENDING, MATCHED, NOT_FOUND, FAILED }
    }

    /** One pasted link's status during a run. */
    data class LinkProgress(
        val raw: String,
        val status: Status,
        /** Failure reason, or the resulting "name · N tracks" on success. */
        val detail: String? = null,
    ) {
        enum class Status { PENDING, RUNNING, DONE, FAILED }
    }

    /** One link's end state in the run summary. */
    data class LinkResult(
        val raw: String,
        /** Created playlist id (-1 when the link failed). */
        val playlistId: Long,
        val playlistName: String,
        val imported: Int,
        val missed: Int,
        /** "Artist — Title" lines of tracks that never matched. */
        val unmatched: List<String> = emptyList(),
        /** Set when the link failed — [playlistId] is -1 then. */
        val error: String? = null,
    )

    sealed interface Phase {
        /** Waiting for pasted links. */
        data object Idle : Phase

        /** Fetching the embed page of link [index]+1 of [count]. */
        data class Fetching(val index: Int, val count: Int) : Phase

        /** YouTube-matching link [index]+1 of [count] in flight. */
        data class Matching(
            val index: Int,
            val count: Int,
            val playlistName: String,
            val rows: List<ImportRow>,
            val done: Int,
            val total: Int,
        ) : Phase

        /** Run finished — per-link results (successes AND failures). */
        data class Done(val results: List<LinkResult>) : Phase

        /** User-facing failure with a fixed message key (no raw URLs). */
        data class Error(val message: String) : Phase
    }

    data class UiState(
        val input: String = "",
        /** Valid, deduped links parsed from [input]. */
        val validLinks: List<SpotifyPlaylistImporter.ParsedLink> = emptyList(),
        /** Lines that didn't yield a link — flagged under the field. */
        val invalidLines: List<SpotifyPlaylistImporter.InvalidLine> = emptyList(),
        /** Per-link status list, populated once a run starts. */
        val links: List<LinkProgress> = emptyList(),
        val phase: Phase = Phase.Idle,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var importJob: Job? = null

    fun onInputChanged(input: String) {
        val parsed = importer.parseImportLinks(input)
        _state.update {
            it.copy(input = input, validLinks = parsed.links, invalidLines = parsed.invalid)
        }
    }

    fun start() {
        if (importJob?.isActive == true) return
        val links = _state.value.validLinks
        if (links.isEmpty()) {
            _state.update {
                it.copy(phase = Phase.Error("Paste at least one Spotify playlist link."))
            }
            return
        }
        importJob = viewModelScope.launch { runImport(links) }
    }

    /** Cancels an in-flight fetch/match and returns to the paste field. */
    fun cancel() {
        importJob?.cancel()
        importJob = null
        _state.update { it.copy(phase = Phase.Idle, links = emptyList()) }
    }

    /** Fully fresh state — the sheet calls this when it reopens after a
     *  finished/errored run so the user gets a new import, not the old
     *  summary (which also auto-opened the previous playlist). */
    fun reset() {
        importJob?.cancel()
        importJob = null
        _state.value = UiState()
    }

    private suspend fun runImport(links: List<SpotifyPlaylistImporter.ParsedLink>) {
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
        _state.update {
            it.copy(
                links = links.map { link -> LinkProgress(link.raw, LinkProgress.Status.PENDING) },
            )
        }
        // Names already taken — seeds the " (2)"/" (3)" suffixing; the set
        // grows with every playlist this run creates so two same-titled
        // imports never collide either.
        val takenNames = runCatching {
            playbackRepository.observePlaylists().first()
                .mapTo(HashSet()) { it.name }
        }.getOrDefault(hashSetOf())

        val results = mutableListOf<LinkResult>()
        links.forEachIndexed { index, link ->
            setLink(index, LinkProgress.Status.RUNNING)
            results += importOne(index, links.size, link, takenNames)
        }
        _state.update { it.copy(phase = Phase.Done(results.toList())) }
    }

    /** One link through the whole pipeline; failures mark it and return a
     *  failed [LinkResult] without aborting the run. */
    private suspend fun importOne(
        index: Int,
        count: Int,
        link: SpotifyPlaylistImporter.ParsedLink,
        takenNames: MutableSet<String>,
    ): LinkResult {
        fun fail(message: String, playlistName: String = ""): LinkResult {
            setLink(index, LinkProgress.Status.FAILED, message)
            return LinkResult(link.raw, -1, playlistName, 0, 0, error = message)
        }

        _state.update { it.copy(phase = Phase.Fetching(index, count)) }
        val playlist = try {
            importer.import(link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyPlaylistImporter.SpotifyImportException) {
            return fail(messageFor(e.kind))
        } catch (e: Exception) {
            return fail("Couldn't fetch that playlist.")
        }
        if (playlist.tracks.isEmpty()) {
            return fail("That playlist looks empty.", playlist.name)
        }

        val rows = playlist.tracks.map { ImportRow(it) }.toMutableList()
        _state.update {
            it.copy(
                phase = Phase.Matching(
                    index = index,
                    count = count,
                    playlistName = playlist.name,
                    rows = rows.toList(),
                    done = 0,
                    total = rows.size,
                ),
            )
        }

        val matchResults = try {
            runner.run(playlist.tracks) { i, match ->
                rows[i] = rows[i].copy(
                    status = when (match) {
                        is SpotifyImportRunner.Match.Matched -> ImportRow.Status.MATCHED
                        is SpotifyImportRunner.Match.NotFound -> ImportRow.Status.NOT_FOUND
                        is SpotifyImportRunner.Match.Failed -> ImportRow.Status.FAILED
                    },
                    artworkUri = (match as? SpotifyImportRunner.Match.Matched)
                        ?.item?.artworkUri
                        ?: rows[i].artworkUri,
                )
                val done = rows.count { it.status != ImportRow.Status.PENDING }
                _state.update {
                    it.copy(
                        phase = Phase.Matching(
                            index = index,
                            count = count,
                            playlistName = playlist.name,
                            rows = rows.toList(),
                            done = done,
                            total = rows.size,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("Import failed while matching tracks.", playlist.name)
        }

        // Matched rows first — no playlist is created when nothing matched,
        // so a dead run can't leave a 0-track playlist behind.
        val now = System.currentTimeMillis()
        val unmatched = mutableListOf<String>()
        val matched = mutableListOf<QueueItem>()
        matchResults.forEachIndexed { i, match ->
            val item = (match as? SpotifyImportRunner.Match.Matched)?.item
            val track = playlist.tracks[i]
            if (item?.videoId == null) {
                unmatched += listOfNotNull(track.artist, track.title)
                    .filter { it.isNotBlank() }
                    .joinToString(" — ")
                    .ifBlank { track.title }
                return@forEachIndexed
            }
            matched += queueItemFor(matched.size, track, item, now)
        }
        if (matched.isEmpty()) {
            return fail("No playable YouTube matches for this playlist.", playlist.name)
        }

        val name = uniqueName(playlist.name.ifBlank { "Spotify import" }, takenNames)
        val playlistId = runCatching { playbackRepository.createPlaylist(name) }
            .getOrDefault(-1L)
        if (playlistId < 0) {
            return fail("Couldn't create the playlist.", name)
        }
        var added = 0
        matched.forEach { item ->
            if (runCatching {
                playbackRepository.addToPlaylist(playlistId, item)
            }.isSuccess
            ) {
                added++
            }
        }

        val missed = matchResults.count { it !is SpotifyImportRunner.Match.Matched }
        setLink(index, LinkProgress.Status.DONE, "“$name” · $added tracks")
        return LinkResult(
            raw = link.raw,
            playlistId = playlistId,
            playlistName = name,
            imported = added,
            missed = missed,
            unmatched = unmatched,
        )
    }

    private fun setLink(
        index: Int,
        status: LinkProgress.Status,
        detail: String? = null,
    ) {
        _state.update { s ->
            s.copy(
                links = s.links.mapIndexed { i, lp ->
                    if (i == index) lp.copy(status = status, detail = detail) else lp
                },
            )
        }
    }

    /** First free name: "Mix", then "Mix (2)", "Mix (3)", … */
    private fun uniqueName(base: String, taken: MutableSet<String>): String {
        var candidate = base
        var n = 2
        while (!taken.add(candidate)) candidate = "$base (${n++})"
        return candidate
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
