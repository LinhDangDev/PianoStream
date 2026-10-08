package com.pianostream.app

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.local.api.LocalTrackWindowSource
import com.pianostream.core.local.api.LocalWindowsResult
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.media.local.LocalMediaItemFactory
import com.pianostream.core.media.queue.QueueAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppViewModelTest {

    /** Records every durable-queue rewrite; queue contents seed via ctor. */
    private class FakePlaybackRepo(
        initialQueue: List<QueueItem> = emptyList(),
        private val cursor: QueueState? = null,
    ) : PlaybackRepository {
        private val queue = MutableStateFlow(initialQueue)
        val replaceCalls = mutableListOf<Pair<List<QueueItem>, QueueState?>>()

        override fun observeQueue(): Flow<List<QueueItem>> = queue
        override fun observeQueueState(): Flow<QueueState?> = flowOf(cursor)
        override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {
            replaceCalls += items to state
            queue.value = items
        }
        override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {}
        override suspend fun saveQueueCursor(state: QueueState) {}
        override fun observeHistory(): Flow<List<PlayHistoryItem>> = flowOf(emptyList())
        override suspend fun appendHistory(item: PlayHistoryItem) {}
        override fun observeFavorites(): Flow<List<FavoriteItem>> = flowOf(emptyList())
        override suspend fun addFavorite(item: FavoriteItem) {}
        override suspend fun removeFavorite(mediaId: String) {}
        override suspend fun isFavorite(mediaId: String) = false
        override suspend fun reconcileMissing(existingMediaIds: Set<String>) {}
        override suspend fun clearStreamCache() {}
        override suspend fun clearAll() {}
    }

    /**
     * Empty-queue `SimpleBasePlayer` for the append path. [handleAddMediaItems]
     * must mutate the backing state itself — the base class re-reads
     * [getState] after the future resolves (the default throws).
     */
    @UnstableApi
    private class QueuePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var _state: State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                    )
                    .build(),
            )
            .setPlaylist(emptyList())
            .setPlaybackState(Player.STATE_IDLE)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        private var nextUid = 0

        override fun getState(): State = _state

        /**
         * SimpleBasePlayer pulls the post-operation state from [getState]
         * after the returned future resolves — the handler must apply the
         * playlist change to the backing state itself.
         */
        override fun handleAddMediaItems(
            index: Int,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<*> {
            val playlist = _state.playlist.toMutableList()
            playlist.addAll(
                index.coerceIn(0, playlist.size),
                mediaItems.map {
                    MediaItemData.Builder("test-uid-${nextUid++}").setMediaItem(it).build()
                },
            )
            _state = _state.buildUpon().setPlaylist(playlist).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
            Futures.immediateVoidFuture()
    }

    private class FakeController(val queuePlayer: QueuePlayer) : PlaybackController {
        var connectCalls = 0
        override val player: Player get() = queuePlayer
        override val isPlaylistReady: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun connect(): Boolean {
            connectCalls++
            return true
        }
        override fun release() {}
    }

    private lateinit var settings: SettingsDataStore
    private lateinit var player: QueuePlayer
    private lateinit var controller: FakeController

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        settings = SettingsDataStore.default(ctx)
        player = QueuePlayer()
        controller = FakeController(player)
    }

    private fun assembler() = QueueAssembler(
        localItems = LocalMediaItemFactory(RuntimeEnvironment.getApplication()),
        // Every local key resolves to "missing" — local rows tombstone (B1).
        windowSource = object : LocalTrackWindowSource {
            override suspend fun windows(contentKeys: List<String>) =
                LocalWindowsResult(emptyList(), contentKeys.size)
        },
        artworkOf = { null },
    )

    private fun streamItem(videoId: String, position: Int = 0) = QueueItem(
        position = position,
        mediaId = "pianostream://stream/$videoId",
        title = "Track $videoId",
        artist = "Someone",
        artworkUri = null,
        source = MediaSource.YOUTUBE,
        durationMs = 200_000L,
        addedAt = 0L,
    )

    private fun vm(repo: PlaybackRepository) =
        AppViewModel(controller, repo, assembler(), settings)

    @Test
    fun `enqueue appends to the live player and rewrites durable queue as existing+new`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val existing = streamItem("aaaaaaaaaaa")
        val cursor = QueueState(
            currentIndex = 0, positionMs = 12_000,
            repeatMode = Player.REPEAT_MODE_OFF, shuffle = false, updatedAt = 42L,
        )
        val repo = FakePlaybackRepo(listOf(existing), cursor)
        val vm = vm(repo)
        vm.connectIfNeeded()

        vm.enqueue(listOf(streamItem("bbbbbbbbbbb")))

        // Durable: one atomic rewrite — old rows first, appended row at the
        // next position, saved cursor passed through (never wiped).
        assertThat(repo.replaceCalls).hasSize(1)
        val (rows, state) = repo.replaceCalls[0]
        assertThat(rows.map { it.mediaId }).containsExactly(
            "pianostream://stream/aaaaaaaaaaa",
            "pianostream://stream/bbbbbbbbbbb",
        ).inOrder()
        assertThat(rows.map { it.position }).containsExactly(0, 1).inOrder()
        assertThat(state).isEqualTo(cursor)

        // Live player: appended at the end, nothing started playing. The
        // fake starts empty (a real connect() would have restored the one
        // durable row first).
        assertThat(player.mediaItemCount).isEqualTo(1)
        assertThat(player.getMediaItemAt(0).mediaId)
            .isEqualTo("pianostream://stream/bbbbbbbbbbb")
        assertThat(player.isPlaying).isFalse()
    }

    @Test
    fun `enqueue without a connected player only persists (M9 restore owns the timeline)`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakePlaybackRepo()
        val vm = vm(repo)

        vm.enqueue(listOf(streamItem("ccccccccccc")))

        assertThat(repo.replaceCalls).hasSize(1)
        assertThat(repo.replaceCalls[0].first.map { it.mediaId })
            .containsExactly("pianostream://stream/ccccccccccc")
        // Never binds a session just to append — connect() stays untouched
        // and the cold-start restore will load the row instead.
        assertThat(controller.connectCalls).isEqualTo(0)
        assertThat(player.mediaItemCount).isEqualTo(0)
    }

    @Test
    fun `enqueue dead local row persists but adds nothing to the player`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakePlaybackRepo()
        val vm = vm(repo)
        vm.connectIfNeeded()

        vm.enqueue(
            listOf(
                QueueItem(
                    position = 0,
                    mediaId = "pianostream://local/gonekey",
                    title = "Deleted track",
                    artist = null,
                    artworkUri = null,
                    source = MediaSource.LOCAL,
                    durationMs = null,
                    addedAt = 0L,
                ),
            ),
        )

        // Row still lands in the durable queue (same as playQueue) — the
        // reconcile pass tombstones it later; the player is untouched.
        assertThat(repo.replaceCalls).hasSize(1)
        assertThat(repo.replaceCalls[0].first.map { it.mediaId })
            .containsExactly("pianostream://local/gonekey")
        assertThat(player.mediaItemCount).isEqualTo(0)
    }

    @Test
    fun `enqueue an empty list is a no-op`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakePlaybackRepo()
        val vm = vm(repo)

        vm.enqueue(emptyList())

        assertThat(repo.replaceCalls).isEmpty()
        assertThat(player.mediaItemCount).isEqualTo(0)
    }
}
