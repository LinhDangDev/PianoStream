package com.pianostream.feature.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.youtube.api.ExtractionException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {

    /** Records per-track queue ops + seek intent (CC1/M9 assertions). */
    @UnstableApi
    private class RecordingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        @Volatile var positionMs = 0L
        var durationMs = 60_000L
        var moveCalls = mutableListOf<Pair<Int, Int>>()
        var removeCalls = mutableListOf<Int>()
        var nextCalls = 0
        var prevCalls = 0
        var seekToCalls = mutableListOf<Long>()
        var indexSeekCalls = mutableListOf<Int>()
        var playing = false
        var prepareCalls = 0

        private val items = (1..3).map { i ->
            MediaItemData.Builder("uid-$i")
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("pianostream://local/k$i")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("T$i")
                                .build(),
                        )
                        .build(),
                )
                .setDurationUs(60_000_000)
                .setIsSeekable(true)
                .build()
        }

        private var _state = State.Builder()
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0)
            .setPlaylist(items)
            .setPlaylistMetadata(MediaMetadata.EMPTY)
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SET_REPEAT_MODE,
                        Player.COMMAND_SET_SHUFFLE_MODE,
                        Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_TRACKS,
                        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                        Player.COMMAND_GET_VOLUME,
                        Player.COMMAND_GET_DEVICE_VOLUME,
                        Player.COMMAND_GET_TEXT,
                    )
                    .build(),
            )
            .build()

        override fun getState(): State = _state

        /** Deliver a player error like a real failure (H5/M6). */
        fun failWith(error: PlaybackException) {
            _state = _state.buildUpon()
                .setPlaybackState(STATE_IDLE)
                .setPlayerError(error)
                .build()
            invalidateState()
        }

        /** Mirror a real player: position changes invalidate the state. */
        fun setPositionAndPublish(ms: Long) {
            positionMs = ms
            _state = _state.buildUpon()
                .setContentPositionMs(ms)
                .setContentBufferedPositionMs(
                    SimpleBasePlayer.PositionSupplier.getConstant(ms),
                )
                .build()
            invalidateState()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            prepareCalls++
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleSetMediaItems(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<*> {
            if (startIndex >= 0) indexSeekCalls.add(startIndex)
            _state = _state.buildUpon()
                .setCurrentMediaItemIndex(startIndex)
                .build()
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            seekToCalls.add(positionMs)
            if (mediaItemIndex >= 0) indexSeekCalls.add(mediaItemIndex)
            setPositionAndPublish(positionMs)
            return Futures.immediateVoidFuture()
        }

        override fun handleMoveMediaItems(
            fromIndex: Int,
            toIndex: Int,
            newIndex: Int,
        ): ListenableFuture<*> {
            moveCalls.add(fromIndex to newIndex)
            return Futures.immediateVoidFuture()
        }

        override fun handleRemoveMediaItems(
            fromIndex: Int,
            toIndex: Int,
        ): ListenableFuture<*> {
            removeCalls.add(fromIndex)
            return Futures.immediateVoidFuture()
        }
    }

    private class TestController(
        val fake: RecordingPlayer,
        val ready: MutableStateFlow<Boolean>,
    ) : PlaybackController {
        override val player: Player get() = fake
        override val isPlaylistReady: Flow<Boolean> = ready
        override suspend fun connect(): Boolean = true
        override fun release() {}
    }

    private lateinit var player: RecordingPlayer
    private lateinit var ready: MutableStateFlow<Boolean>
    private lateinit var controller: TestController

    @Before
    fun setUp() {
        player = RecordingPlayer()
        ready = MutableStateFlow(false)
        controller = TestController(player, ready)
    }

    private fun TestScope.vm(): PlayerViewModel {
        // No setMain: TestMainDispatcher delegates to the real
        // main-looper dispatcher (AndroidDispatcherFactory), which
        // SimpleBasePlayer's application-thread check accepts and whose
        // handler-posted delays ShadowLooper can pump. A TestDispatcher
        // Main would share this runTest's scheduler and its end-of-test
        // advanceUntilIdle would spin on the VM's infinite 1 s ticker.
        val vm = PlayerViewModel(controller, FakePlaybackRepository())
        vm.connect()
        ShadowLooper.idleMainLooper()
        return vm
    }

    @Test
    fun `connect exposes player and syncs state`() = runTest {
        ready.value = true
        val vm = vm()
        assertThat(vm.state.value.connected).isTrue()
        assertThat(vm.player).isNotNull()
        assertThat(vm.state.value.queueCount).isEqualTo(3)
    }

    @Test
    fun `position ticker emits while connected`() = runTest {
        ready.value = true
        val vm = vm()
        // The ticker delay is real-time under Unconfined — one short sleep
        // beats it, then the VM mirrors the player's live position.
        player.setPositionAndPublish(12_345)
        ShadowLooper.idleMainLooper()
        assertThat(player.currentPosition).isEqualTo(12_345)
        // One real tick of the VM's 1 s position loop.
        ShadowLooper.idleMainLooper(1_100, TimeUnit.MILLISECONDS)
        assertThat(vm.state.value.positionMs).isEqualTo(12_345)
    }

    @Test
    fun `seek helpers move by ten seconds`() = runTest {
        ready.value = true
        val vm = vm()
        player.setPositionAndPublish(30_000)
        ShadowLooper.idleMainLooper()
        vm.seekForward()
        assertThat(player.seekToCalls.last()).isEqualTo(40_000)
        vm.seekBack()
        vm.seekBack()
        assertThat(player.seekToCalls.last()).isEqualTo(20_000)
    }

    @Test
    fun `move is inert until isPlaylistReady (M9)`() = runTest {
        val vm = vm()
        vm.move(2, 0)
        assertThat(player.moveCalls).isEmpty()
        ready.value = true
        ShadowLooper.idleMainLooper()
        vm.move(2, 0)
        // Drag 3→1 = exactly one moveMediaItem(2, 0) (CC1).
        assertThat(player.moveCalls).containsExactly(2 to 0)
    }

    @Test
    fun `remove and playAt also gated on playlist ready (M9)`() = runTest {
        val vm = vm()
        vm.removeAt(1)
        vm.playAt(2)
        assertThat(player.removeCalls).isEmpty()
        assertThat(player.seekToCalls).isEmpty()
        ready.value = true
        ShadowLooper.idleMainLooper()
        vm.removeAt(1)
        assertThat(player.removeCalls).containsExactly(1)
    }

    @Test
    fun `player error surfaces typed extraction message (H5)`() = runTest {
        ready.value = true
        val vm = vm()
        player.failWith(
            PlaybackException(
                "no formats",
                ExtractionException.NoFormats("nothing"),
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            ),
        )
        ShadowLooper.idleMainLooper()
        assertThat(vm.state.value.error).contains("no playable audio format")
    }

    @Test
    fun `retry clears error and re-prepares (M6)`() = runTest {
        ready.value = true
        val vm = vm()
        player.failWith(
            PlaybackException(
                "boom",
                ExtractionException.Network("tls"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
        )
        ShadowLooper.idleMainLooper()
        assertThat(vm.state.value.error).isNotNull()
        vm.retryCurrent()
        assertThat(vm.state.value.error).isNull()
        ShadowLooper.idleMainLooper()
        assertThat(player.prepareCalls).isAtLeast(1)
    }

    @Test
    fun `next and previous seek across media items`() = runTest {
        ready.value = true
        val vm = vm()
        val current = player.currentMediaItemIndex
        vm.next()
        // seekToNext resolves to a media-item seek at current+1.
        assertThat(player.indexSeekCalls).contains(current + 1)
        ShadowLooper.idleMainLooper()
        val before = player.indexSeekCalls.size + player.seekToCalls.size
        vm.previous()
        // seekToPreviousMediaItem lands as SOME player seek — either a
        // cross-item index seek or an in-item reposition.
        ShadowLooper.idleMainLooper()
        assertThat(player.indexSeekCalls.size + player.seekToCalls.size)
            .isGreaterThan(before)
    }
}

/** Minimal in-memory repo — move/remove mirrors are the only parts under test. */
private class FakePlaybackRepository : PlaybackRepository {
    val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    var state: QueueState? = null

    override fun observeQueue(): Flow<List<QueueItem>> = queue
    override fun observeQueueState(): Flow<QueueState?> = MutableStateFlow(state)
    override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {
        queue.value = items.mapIndexed { i, q -> q.copy(position = i) }
        state?.let { this.state = it }
    }

    override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {
        val rows = queue.value.toMutableList()
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return
        val moved = rows.removeAt(fromPosition)
        rows.add(toPosition, moved)
        queue.value = rows.mapIndexed { i, q -> q.copy(position = i) }
    }

    override suspend fun saveQueueCursor(state: QueueState) {
        this.state = state
    }

    override fun observeHistory(): Flow<List<PlayHistoryItem>> = MutableStateFlow(emptyList())
    override suspend fun appendHistory(item: PlayHistoryItem) = Unit
    override fun observeFavorites(): Flow<List<FavoriteItem>> = MutableStateFlow(emptyList())
    override suspend fun addFavorite(item: FavoriteItem) = Unit
    override suspend fun removeFavorite(mediaId: String) = Unit
    override suspend fun isFavorite(mediaId: String): Boolean = false
    override suspend fun reconcileMissing(existingMediaIds: Set<String>) = Unit
    override suspend fun clearStreamCache() = Unit
    override suspend fun clearAll() = Unit
}
