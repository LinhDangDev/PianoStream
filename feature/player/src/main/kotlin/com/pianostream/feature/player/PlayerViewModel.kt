package com.pianostream.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.youtube.api.ExtractionException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin state bridge over the app-scoped `PlaybackController.player` (one
 * MediaController per app — never one per screen, never a `Player` held by
 * a composable). Position ticks at 1 Hz while playing; seek helpers move
 * ±10 s; queue ops go through the player (`moveMediaItem` per CC1).
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val isPlaylistReady: Boolean = false,
        val currentItem: MediaItem? = null,
        val currentIndex: Int = -1,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val shuffle: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val queueCount: Int = 0,
        val error: String? = null,
        /** Current item is YouTube-backed (stream or video URI) — Song/Video toggle applies. */
        val videoCapable: Boolean = false,
        /** Current item resolves through the `pianostream://video/` scheme. */
        val videoMode: Boolean = false,
        /** Current item is saved in the collection (favorites). */
        val isFavorite: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope get() = viewModelScope
    private var ticker: Job? = null
    private val favoriteIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            playbackRepository.observeFavorites().collect { favs ->
                favoriteIds.value = favs.mapTo(HashSet()) { it.mediaId }
                // refresh heart state for whatever is playing right now
                _state.update { st ->
                    st.copy(isFavorite = st.currentItem?.mediaId in favoriteIds.value)
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.update { it.copy(error = describeError(error)) }
        }
    }

    /** [Player] handle for media3-ui-compose state holders; null pre-connect. */
    val player: Player? get() = if (_state.value.connected) controller.player else null

    fun connect() {
        if (_state.value.connected) return
        scope.launch {
            val ok = runCatching { controller.connect() }.getOrDefault(false)
            if (ok) {
                controller.player.addListener(listener)
                _state.update { it.copy(connected = true) }
                sync(controller.player)
                startTicker()
                scope.launch {
                    controller.isPlaylistReady.collect { ready ->
                        _state.update { it.copy(isPlaylistReady = ready) }
                    }
                }
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                runCatching { sync(controller.player) }
            }
        }
    }

    private fun sync(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        _state.update {
            it.copy(
                currentItem = player.currentMediaItem,
                currentIndex = player.currentMediaItemIndex,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                queueCount = player.mediaItemCount,
                videoCapable = videoIdOf(mediaId) != null,
                videoMode = mediaId.startsWith(VIDEO_URI_PREFIX),
                isFavorite = mediaId in favoriteIds.value,
            )
        }
    }

    /** Save/remove the playing item in the collection (Favorites tab). */
    fun toggleFavorite() {
        val item = _state.value.currentItem ?: return
        val mediaId = item.mediaId
        scope.launch {
            if (mediaId in favoriteIds.value) {
                playbackRepository.removeFavorite(mediaId)
            } else {
                val meta = item.mediaMetadata
                playbackRepository.addFavorite(
                    FavoriteItem(
                        mediaId = mediaId,
                        title = meta.title?.toString() ?: mediaId,
                        artist = meta.artist?.toString(),
                        artworkUri = meta.artworkUri?.toString(),
                        source = if (mediaId.startsWith("pianostream://local/")) {
                            MediaSource.LOCAL
                        } else {
                            MediaSource.YOUTUBE
                        },
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
            // optimistic — observeFavorites will confirm shortly
            _state.update { it.copy(isFavorite = mediaId !in favoriteIds.value) }
        }
    }

    /** H5/M6: typed message for extraction failures; fallback otherwise. */
    private fun describeError(e: androidx.media3.common.PlaybackException): String =
        when (val c = e.cause) {
            is ExtractionException.NoFormats ->
                "This video has no playable audio format."
            is ExtractionException.PoTokenRequired ->
                "YouTube asked for a verification token — try again."
            is ExtractionException.RateLimited ->
                "YouTube is rate-limiting requests — wait a moment."
            is ExtractionException.CipherFailure ->
                "Couldn't decode the stream signature."
            is ExtractionException.Network ->
                "Network error reaching YouTube."
            is ExtractionException.AllClientsFailed ->
                "Every YouTube client failed to resolve the stream."
            is ExtractionException -> c.message ?: "Couldn't resolve the stream."
            else -> e.message ?: "Playback failed."
        }

    /** Clear the error and re-prepare the current item (resolve, forced). */
    fun retryCurrent() {
        if (!_state.value.connected) return
        _state.update { it.copy(error = null) }
        controller.player.prepare()
    }

    fun playPause() {
        controller.player.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) {
        controller.player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) {
        val p = controller.player
        seekTo((p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0)))
    }

    fun seekForward() = seekBy(10_000)
    fun seekBack() = seekBy(-10_000)
    fun next() = controller.player.seekToNextMediaItem()
    fun previous() = controller.player.seekToPreviousMediaItem()

    fun toggleShuffle() {
        val p = controller.player
        p.shuffleModeEnabled = !p.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val p = controller.player
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Queue ops — inert while `isPlaylistReady` is false (M9).
     *  Every mutation ALSO mirrors into `queue_items` (B9): the durable
     *  queue is the only cold-start source, so a reorder/remove that only
     *  touched the player would resurrect the stale order after kill. */
    fun move(from: Int, to: Int) {
        if (!_state.value.isPlaylistReady) return
        controller.player.moveMediaItem(from, to)
        scope.launch { runCatching { playbackRepository.moveQueueItem(from, to) } }
    }

    fun removeAt(index: Int) {
        if (!_state.value.isPlaylistReady) return
        controller.player.removeMediaItem(index)
        scope.launch {
            runCatching {
                val remaining = playbackRepository.observeQueue().first()
                    .filterIndexed { i, _ -> i != index }
                playbackRepository.replaceQueue(remaining, null)
            }
        }
    }

    fun playAt(index: Int) {
        if (!_state.value.isPlaylistReady) return
        controller.player.seekTo(index, 0)
    }

    /**
     * Song/Video toggle (YouTube items only): swaps the current item's
     * mediaId between `pianostream://stream/` and `pianostream://video/` at
     * the same queue index, re-seeking to the live position so the switch
     * is seamless. `VIDEO_URI_PREFIX` mirrors the resolver's `video` host;
     * local items and redundant toggles return early. A failed video
     * resolve surfaces through the usual error card.
     */
    fun setVideoMode(video: Boolean) {
        if (!_state.value.connected) return
        val p = controller.player
        val index = p.currentMediaItemIndex
        val item = p.currentMediaItem ?: return
        val videoId = videoIdOf(item.mediaId) ?: return
        val wasVideo = item.mediaId.startsWith(VIDEO_URI_PREFIX)
        if (video == wasVideo) return
        val newId = if (video) VIDEO_URI_PREFIX + videoId else PianoStreamUri.stream(videoId)
        val position = p.currentPosition.coerceAtLeast(0)
        p.replaceMediaItem(index, item.buildUpon().setMediaId(newId).setUri(newId).build())
        p.seekTo(index, position)
        _state.update { it.copy(videoMode = video, videoCapable = true) }
    }

    override fun onCleared() {
        ticker?.cancel()
        runCatching { controller.player.removeListener(listener) }
    }

    private companion object {
        const val VIDEO_URI_PREFIX: String = "pianostream://video/"

        /** videoId from a `stream` or `video` URI; null for local/other items. */
        fun videoIdOf(mediaId: String): String? = when {
            mediaId.startsWith(VIDEO_URI_PREFIX) ->
                mediaId.removePrefix(VIDEO_URI_PREFIX).takeIf(PianoStreamUri::isValidId)
            else ->
                (PianoStreamUri.parse(mediaId) as? PianoStreamUri.Id.Stream)?.videoId
        }
    }
}
