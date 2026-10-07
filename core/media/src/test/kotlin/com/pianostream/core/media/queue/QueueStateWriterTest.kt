package com.pianostream.core.media.queue

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.playback.model.QueueState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * H6 — 5 s position writer + mandatory writes on transition/discontinuity/
 * pause/task-removal; queue-mutation writes debounced to ≤1/s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueStateWriterTest {

    private fun state(index: Int = 0, position: Long = 10_000) = QueueState(
        currentIndex = index,
        positionMs = position,
        repeatMode = 0,
        shuffle = false,
        updatedAt = 1_000,
    )

    private class Recorder {
        val writes = mutableListOf<QueueState>()
        suspend fun record(s: QueueState) { writes += s }
    }

    @Test
    fun persist_blocking_writes_current_snapshot() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { state() }, rec::record)
        writer.persistBlocking()
        runCurrent()
        assertThat(rec.writes).containsExactly(state())
    }

    @Test
    fun null_snapshot_writes_nothing() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { null }, rec::record)
        writer.persistBlocking()
        runCurrent()
        assertThat(rec.writes).isEmpty()
    }

    @Test
    fun queue_writes_debounced_to_one_per_second() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { state() }, rec::record)
        writer.persistQueueDebounced()
        writer.persistQueueDebounced()
        writer.persistQueueDebounced()
        runCurrent()
        assertThat(rec.writes).hasSize(1)
    }

    @Test
    fun ticker_writes_every_5s_while_playing() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { state() }, rec::record)
        writer.onIsPlayingChanged(true)
        advanceTimeBy(15_500)
        runCurrent()
        // 3 ticks at 5/10/15 s
        assertThat(rec.writes).hasSize(3)
    }

    @Test
    fun pause_stops_ticker() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { state() }, rec::record)
        writer.onIsPlayingChanged(true)
        advanceTimeBy(5_500)
        runCurrent()
        writer.onIsPlayingChanged(false)
        runCurrent()
        val atPause = rec.writes.size
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(rec.writes).hasSize(atPause)
    }

    @Test
    fun transition_and_discontinuity_persist() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { state() }, rec::record)
        writer.playerListener.onMediaItemTransition(
            null, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        runCurrent()
        assertThat(rec.writes).hasSize(1)
        // Position discontinuity with empty infos.
        val info = androidx.media3.common.Player.PositionInfo(
            null, 0, null, null, 0, 0L, 0L, 0, 0,
        )
        writer.playerListener.onPositionDiscontinuity(
            info, info,
            androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK,
        )
        runCurrent()
        assertThat(rec.writes).hasSize(2)
    }

    @Test
    fun stop_cancels_ticker_and_writes_final() = runTest {
        val rec = Recorder()
        val writer = QueueStateWriter(backgroundScope, { state() }, rec::record)
        writer.onIsPlayingChanged(true)
        advanceTimeBy(5_500)
        runCurrent()
        writer.stop()
        runCurrent()
        val atStop = rec.writes.size
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(rec.writes).hasSize(atStop)
    }
}
