package com.pianostream.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope get() = viewModelScope
    private var ticker: Job? = null

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
            )
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

    /** Queue ops — inert while `isPlaylistReady` is false (M9). */
    fun move(from: Int, to: Int) {
        if (!_state.value.isPlaylistReady) return
        controller.player.moveMediaItem(from, to)
    }

    fun removeAt(index: Int) {
        if (!_state.value.isPlaylistReady) return
        controller.player.removeMediaItem(index)
    }

    fun playAt(index: Int) {
        if (!_state.value.isPlaylistReady) return
        controller.player.seekTo(index, 0)
    }

    override fun onCleared() {
        ticker?.cancel()
        runCatching { controller.player.removeListener(listener) }
    }
}
