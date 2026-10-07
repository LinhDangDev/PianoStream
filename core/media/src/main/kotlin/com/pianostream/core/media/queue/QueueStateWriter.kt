package com.pianostream.core.media.queue

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pianostream.core.data.playback.model.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * H6 — position/history writer: 5 s cadence while `isPlaying`, plus mandatory
 * writes on `onIsPlayingChanged(false)`, `onMediaItemTransition`,
 * `onPositionDiscontinuity`, `onTaskRemoved` and `onDestroy`. Position writes
 * are independent of queue mutations; queue rewrites are debounced to ≤1/s
 * and applied as one `@Transaction` (in the repository).
 *
 * Designed headless: [snapshot] is the only dependency on the player — unit
 * tests drive it with a lambda.
 */
class QueueStateWriter(
    private val scope: CoroutineScope,
    private val snapshot: () -> QueueState?,
    private val write: suspend (QueueState) -> Unit,
    private val tickMs: Long = TICK_MS,
) {

    private var ticker: Job? = null
    private var lastWriteAt = 0L

    /** The `Player.Listener` bridge — thin: every event funnels to [persist]. */
    val playerListener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistBlocking()
            syncTicker(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persistBlocking()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            persistBlocking()
        }
    }

    /** Called when playback becomes active/inactive. */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) persistBlocking()
        syncTicker(isPlaying)
    }

    /** Lifecycle saves — called from `onTaskRemoved`/`onDestroy`. */
    fun persistBlocking() {
        val state = snapshot() ?: return
        // Single-flight: writes are serialized through the writer scope.
        scope.launch { write(state) }
        lastWriteAt = System.currentTimeMillis()
    }

    /** Queue-mutation save, debounced to ≤1 write/s (H6). */
    fun persistQueueDebounced() {
        val now = System.currentTimeMillis()
        if (now - lastWriteAt < QUEUE_DEBOUNCE_MS) return
        lastWriteAt = now
        val state = snapshot() ?: return
        scope.launch { write(state) }
    }

    private fun syncTicker(playing: Boolean) {
        if (playing && ticker == null) {
            ticker = scope.launch {
                while (isActive) {
                    delay(tickMs)
                    snapshot()?.let { write(it) }
                    lastWriteAt = System.currentTimeMillis()
                }
            }
        } else if (!playing) {
            ticker?.cancel()
            ticker = null
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        persistBlocking()
    }

    companion object {
        const val TICK_MS = 5_000L
        const val QUEUE_DEBOUNCE_MS = 1_000L
    }
}
