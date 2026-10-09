package com.pianostream.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.media.sleep.SleepTimerMode
import com.pianostream.core.media.sleep.SleepTimerState
import com.pianostream.core.media.video.VideoQualityStore
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
    private val videoQualityStore: VideoQualityStore,
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
        /** Video-quality tier itag from `VideoQualityStore` (null = auto). */
        val videoQuality: Int? = null,
        /** Current item is saved in the collection (favorites). */
        val isFavorite: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope get() = viewModelScope
    private var ticker: Job? = null
    private val favoriteIds = MutableStateFlow<Set<String>>(emptySet())

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    init {
        scope.launch {
            videoQualityStore.itag.collect { tag ->
                _state.update { it.copy(videoQuality = tag) }
            }
        }
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

        // Individual callbacks run before the batched onEvents -> sync, so
        // _state.currentItem still holds the PREVIOUS item inside them.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val timer = _sleepTimer.value
            if (!timer.active || timer.mode !is SleepTimerMode.EndOfTrack) return
            val previousId = _state.value.currentItem?.mediaId
            if (mediaItem != null && mediaItem.mediaId != previousId) {
                fireSleepTimer()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val timer = _sleepTimer.value
            if (playbackState == Player.STATE_ENDED &&
                timer.active && timer.mode is SleepTimerMode.EndOfTrack
            ) {
                fireSleepTimer()
            }
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
                tickSleepTimer()
            }
        }
    }

    /** Reuses the shared 1 s ticker; starts it when idle (timer set pre-connect). */
    private fun ensureTicker() {
        if (ticker?.isActive != true) startTicker()
    }

    /**
     * Arms the sleep timer, replacing any active one. `AfterMinutes` counts
     * down on the shared ticker (1 s per tick) and pauses on expiry;
     * `EndOfTrack` pauses when the current track ends (transition to
     * another item, or STATE_ENDED — see [listener]).
     */
    fun setSleepTimer(mode: SleepTimerMode) {
        when (mode) {
            is SleepTimerMode.AfterMinutes -> {
                val durationMs = mode.minutes.coerceAtLeast(0) * 60_000L
                _sleepTimer.value =
                    SleepTimerState(active = true, remainingMs = durationMs, mode = mode)
                ensureTicker()
            }
            SleepTimerMode.EndOfTrack -> {
                _sleepTimer.value = SleepTimerState(active = true, mode = mode)
            }
        }
    }

    fun cancelSleepTimer() {
        _sleepTimer.value = SleepTimerState()
    }

    private fun tickSleepTimer() {
        val timer = _sleepTimer.value
        if (!timer.active || timer.mode !is SleepTimerMode.AfterMinutes) return
        val remaining = timer.remainingMs - TICK_MS
        if (remaining <= 0) {
            fireSleepTimer()
        } else {
            _sleepTimer.update { it.copy(remainingMs = remaining) }
        }
    }

    /** Pause + disarm; shared by AfterMinutes expiry and EndOfTrack transitions. */
    private fun fireSleepTimer() {
        runCatching { controller.player.pause() }
        cancelSleepTimer()
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
     * Clears every queued item except the currently playing one (B9 mirror:
     * the durable queue is rewritten FIRST, then the live player). The
     * cursor is read fresh and passed straight back — `replaceQueue(items,
     * null)` would delete `queue_state` (H6), and a cached cursor could be
     * stale by the time this coroutine runs. Player-side removal only
     * happens while connected, matching `AppViewModel.enqueue`'s rule that
     * an unconnected player is repopulated by the M9 cold-start restore.
     */
    fun clearQueue() {
        scope.launch {
            val p = if (_state.value.connected) controller.player else null
            val rows = playbackRepository.observeQueue().first()
            val current = p?.currentMediaItem
            val kept: List<QueueItem> = if (current == null) {
                emptyList()
            } else {
                val index = p.currentMediaItemIndex
                val byIndex = rows.getOrNull(index)
                if (byIndex != null && byIndex.mediaId == current.mediaId) {
                    listOf(byIndex)
                } else {
                    rows.filter { it.mediaId == current.mediaId }
                }
            }
            val cursor = playbackRepository.observeQueueState().first()
            runCatching {
                playbackRepository.replaceQueue(
                    kept.mapIndexed { i, q -> q.copy(position = i) },
                    cursor,
                )
            }
            if (p != null) {
                val index = p.currentMediaItemIndex
                val count = p.mediaItemCount
                if (index in 0 until count) {
                    if (index + 1 < count) p.removeMediaItems(index + 1, count)
                    if (index > 0) p.removeMediaItems(0, index)
                } else if (count > 0) {
                    p.clearMediaItems()
                }
            }
        }
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

    /**
     * Quality-menu pick (YouTube items only): stores the new tier itag in
     * `VideoQualityStore` — every later `pianostream://video/` resolve reads
     * it — then re-prepares the CURRENT item at the live position so the
     * video child re-resolves under the new itag (same replace+reseek
     * pattern as [setVideoMode]). A pick made in Song mode just persists and
     * applies when the next video source resolves.
     */
    fun setVideoQuality(itag: Int?) {
        if (itag == _state.value.videoQuality) return
        videoQualityStore.set(itag)
        if (!_state.value.connected || !_state.value.videoMode) return
        val p = controller.player
        val index = p.currentMediaItemIndex
        val item = p.currentMediaItem ?: return
        if (!item.mediaId.startsWith(VIDEO_URI_PREFIX)) return
        val position = p.currentPosition.coerceAtLeast(0)
        p.replaceMediaItem(index, item)
        p.seekTo(index, position)
    }

    override fun onCleared() {
        ticker?.cancel()
        runCatching { controller.player.removeListener(listener) }
    }

    private companion object {
        const val VIDEO_URI_PREFIX: String = "pianostream://video/"
        const val TICK_MS: Long = 1_000

        /** videoId from a `stream` or `video` URI; null for local/other items. */
        fun videoIdOf(mediaId: String): String? = when {
            mediaId.startsWith(VIDEO_URI_PREFIX) ->
                mediaId.removePrefix(VIDEO_URI_PREFIX).takeIf(PianoStreamUri::isValidId)
            else ->
                (PianoStreamUri.parse(mediaId) as? PianoStreamUri.Id.Stream)?.videoId
        }
    }
}
