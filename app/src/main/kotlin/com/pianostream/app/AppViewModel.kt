package com.pianostream.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.media.queue.QueueAssembler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Activity-scoped host ViewModel: owns the ONE app-scoped controller
 * connection (plan C1) so the mini player in [AppScaffold] and every
 * destination drive the same `MediaController` via `PlaybackController`.
 *
 * [playQueue] is the single "user tapped something to play" entry point:
 * the durable queue is written to `playback.db` FIRST (B9 — it is the only
 * cold-start source), then `pianostream://local` keys expand into clipped
 * per-track `MediaItem`s through the same `QueueAssembler` the cold-start
 * restore uses, so UI-submitted plays and restored queues take the
 * identical expansion path (CC1/CC9).
 */
@HiltViewModel
public class AppViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val playbackRepository: PlaybackRepository,
    private val assembler: QueueAssembler,
    settings: SettingsDataStore,
) : ViewModel() {

    private val _player = MutableStateFlow<Player?>(null)
    public val player: StateFlow<Player?> = _player.asStateFlow()

    /** Theme prefs (frozen keys) — read by MainActivity to theme the app. */
    public val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    public val dynamicColor: StateFlow<Boolean> =
        settings.dynamicColor.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    public fun connectIfNeeded() {
        if (_player.value != null) return
        viewModelScope.launch { ensureConnected() }
    }

    private suspend fun ensureConnected(): Player? {
        _player.value?.let { return it }
        if (runCatching { controller.connect() }.getOrDefault(false)) {
            return controller.player.also { _player.value = it }
        }
        return null
    }

    /**
     * Replaces the durable queue with [items] and starts playback at
     * [startIndex] (a row position — dropped local keys are skipped by the
     * assembler and never reached).
     */
    public fun playQueue(items: List<QueueItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val rows = items.mapIndexed { i, item -> item.copy(position = i) }
            // B9 — persist before touching the player; QueueStateWriter keeps
            // the cursor column current from here on.
            runCatching { playbackRepository.replaceQueue(rows, null) }
            val assembled = assembler.assemble(rows)
            if (assembled.items.isEmpty()) return@launch
            val player = ensureConnected() ?: return@launch
            // startIndex is a row position; map it through the assembler's
            // surviving-positions list in case rows tombstoned out.
            val start = assembled.sourcePositions
                .indexOf(startIndex)
                .takeIf { it >= 0 } ?: 0
            player.setMediaItems(assembled.items, start, 0L)
            player.prepare()
            player.play()
        }
    }

    /**
     * Appends [items] to the END of the current queue ("add to queue"): the
     * live player gets `addMediaItems`, and the durable queue is rewritten as
     * (existing rows ++ new rows) so the append survives a cold start (B9).
     * Never interrupts what's playing — and when the queue was empty the
     * rows simply sit queued (no auto-play, no prepare).
     */
    public fun enqueue(items: List<QueueItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val current = playbackRepository.observeQueue().first()
            val rows = items.mapIndexed { i, item ->
                item.copy(position = current.size + i)
            }
            val assembled = assembler.assemble(rows)
            // Persist before touching the player (B9). The cursor is passed
            // back through unchanged — replaceQueue(null) would delete
            // queue_state and lose the saved position (H6).
            val cursor = playbackRepository.observeQueueState().first()
            runCatching { playbackRepository.replaceQueue(current + rows, cursor) }
            if (assembled.items.isEmpty()) return@launch
            // Append to an already-live player only — when not connected, the
            // M9 cold-start restore inside connect() loads these rows itself,
            // so addMediaItems here would double-queue them.
            _player.value?.addMediaItems(assembled.items)
        }
    }
}
