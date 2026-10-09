/*
 * Stub Media3 [Player]. [SimpleBasePlayer] handles every [Player] listener,
 * callback and (un)available-command accounting; a subclass only needs to
 * mutate its own [State] and publish changes.
 */
package com.pianostream.app.fake

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures as GuavaFutures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A [Player] fake for the `stub` source flavor: deterministic playlist state
 * with no decoding, no I/O and no clocks beyond a trivial position ticker.
 * Queue set/add/move/remove, indexed seek and play/pause all behave like a
 * real player so ViewModels and E2E flows exercise the same [Player]
 * contract they see in production.
 *
 * Handler contract (media3 [SimpleBasePlayer]): a `handle*` mutates [_state]
 * (via `buildUpon()`) and returns an immediate future — the base class then
 * re-reads [getState] and fans the diff out to listeners. Two invariants the
 * [State] builder enforces: every [MediaItemData.uid] must be unique, and an
 * empty playlist must be `STATE_IDLE`/`STATE_ENDED` (never `STATE_READY`).
 */
public class FakePlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var ticker: Job? = null

    /**
     * Deterministic playback position (ms). Reads go through the
     * [PositionSupplier] wired into [_state] at build time, so a mutation
     * here plus a state publish is enough — no rebuild needed per tick.
     */
    private var positionMs: Long = 0L

    /**
     * The queue. [State] never exposes the playlist back (it derives a
     * Timeline from it), so the fake keeps the authoritative list — every
     * mutation republishes it through [publishPlaylist].
     */
    private val playlistItems: MutableList<MediaItemData> = mutableListOf()

    /** Monotonic uid source (queue rows are keyed by uid; must be unique). */
    private var nextUid: Long = 0L

    private var _state: State

    init {
        playlistItems.addAll(FAKE_MEDIA_ITEMS.map { mediaItemData(it) })
        _state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_TRACKS,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_GET_VOLUME,
                    Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_GET_TEXT,
                ).build(),
            )
            .setPlaylist(playlistItems.toList())
            .setPlaylistMetadata(MediaMetadata.EMPTY)
            .setContentPositionMs { positionMs }
            .setContentBufferedPositionMs { positionMs }
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun getState(): State = _state

    /**
     * Republishes [_state] after a [playlistItems] mutation: clamps or clears
     * the cursor for the new size and keeps `STATE_ENDED` on an empty queue
     * (`STATE_READY` is invalid on an empty playlist).
     */
    private fun publishPlaylist(currentIndex: Int) {
        _state = _state.buildUpon()
            .setPlaylist(playlistItems.toList())
            .setCurrentMediaItemIndex(
                if (playlistItems.isEmpty()) {
                    C.INDEX_UNSET
                } else {
                    currentIndex.coerceIn(0, playlistItems.size - 1)
                },
            )
            .setPlaybackState(
                if (playlistItems.isEmpty()) Player.STATE_ENDED else Player.STATE_READY,
            )
            .build()
        invalidateState()
    }

    /** The effective cursor (a stored `C.INDEX_UNSET` resolves to item 0). */
    private fun currentItemIndex(): Int =
        _state.currentMediaItemIndex.takeIf { it in playlistItems.indices } ?: 0

    private fun currentItemUid(): Any? =
        playlistItems.getOrNull(currentItemIndex())?.uid

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                positionMs += 1_000
                invalidateState()
            }
        }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (playlistItems.isNotEmpty()) {
            _state = _state.buildUpon()
                .setPlaybackState(Player.STATE_READY)
                .build()
            invalidateState()
        }
        return GuavaFutures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        _state = _state.buildUpon()
            .setPlayWhenReady(
                playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .build()
        if (playWhenReady) startTicker() else {
            ticker?.cancel()
            ticker = null
        }
        invalidateState()
        return GuavaFutures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        _state = _state.buildUpon()
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        ticker?.cancel()
        ticker = null
        invalidateState()
        return GuavaFutures.immediateVoidFuture()
    }

    /**
     * [Player.seekTo] family. [SimpleBasePlayer] routes position-only seeks
     * here with [mediaItemIndex] == [C.INDEX_UNSET]; cross-item seeks (incl.
     * `seekToNext`/`seekToPrevious`, resolved by the base class) pass a real
     * index — both branches update [_state] so the current item follows the
     * seek. The old fake ignored the index, stranding the cursor at item 0.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET && playlistItems.isNotEmpty()) {
            _state = _state.buildUpon()
                .setCurrentMediaItemIndex(
                    mediaItemIndex.coerceIn(0, playlistItems.size - 1),
                )
                .build()
        }
        this.positionMs = if (positionMs == C.TIME_UNSET) 0L else positionMs
        invalidateState()
        return GuavaFutures.immediateVoidFuture()
    }

    /** [Player.setMediaItem]/[Player.setMediaItems]: replace the queue. */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        playlistItems.clear()
        mediaItems.forEach { playlistItems += mediaItemData(it) }
        positionMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        publishPlaylist(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        return GuavaFutures.immediateVoidFuture()
    }

    /** [Player.addMediaItem]/[Player.addMediaItems]: insert at [index]. */
    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        // Players clamp rather than throw when the insertion index runs past
        // the end of the playlist.
        val insertionIndex = index.coerceIn(0, playlistItems.size)
        val cursor = currentItemIndex()
        mediaItems.forEachIndexed { i, item ->
            playlistItems.add(insertionIndex + i, mediaItemData(item))
        }
        publishPlaylist(
            if (insertionIndex <= cursor) cursor + mediaItems.size else cursor,
        )
        return GuavaFutures.immediateVoidFuture()
    }

    /** [Player.moveMediaItem]/[Player.moveMediaItems] family. */
    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        // Follow the playing item's uid across the move, like a real player.
        val currentUid = currentItemUid()
        Util.moveItems(playlistItems, fromIndex, toIndex, newIndex)
        val movedIndex = currentUid
            ?.let { uid -> playlistItems.indexOfFirst { it.uid == uid } }
            ?.takeIf { it >= 0 }
            ?: currentItemIndex()
        publishPlaylist(movedIndex)
        return GuavaFutures.immediateVoidFuture()
    }

    /** [Player.removeMediaItem]/[Player.removeMediaItems] family. */
    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ): ListenableFuture<*> {
        val currentUid = currentItemUid()
        playlistItems.subList(fromIndex, toIndex).clear()
        // If the playing item was removed, the row that slid into its slot
        // becomes current (same rule as ExoPlayer); else the index shifts.
        val cursor = currentUid
            ?.let { uid -> playlistItems.indexOfFirst { it.uid == uid } }
            ?.takeIf { it >= 0 }
            ?: fromIndex.coerceIn(0, (playlistItems.size - 1).coerceAtLeast(0))
        publishPlaylist(cursor)
        return GuavaFutures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        _state = _state.buildUpon().setRepeatMode(repeatMode).build()
        invalidateState()
        return GuavaFutures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(
        shuffleModeEnabled: Boolean,
    ): ListenableFuture<*> {
        _state = _state.buildUpon()
            .setShuffleModeEnabled(shuffleModeEnabled)
            .build()
        invalidateState()
        return GuavaFutures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        ticker?.cancel()
        return GuavaFutures.immediateVoidFuture()
    }

    private fun mediaItemData(item: MediaItem): MediaItemData =
        MediaItemData.Builder("fake-uid-${++nextUid}")
            .setMediaItem(item)
            .setDurationUs(FAKE_DURATION_US)
            .setIsSeekable(true)
            .build()

    public companion object {
        private const val FAKE_DURATION_US: Long = 210_000_000L // 3.5 min

        /** The three tracks a fresh FakePlayer starts with. */
        private val FAKE_MEDIA_ITEMS: List<MediaItem> = List(3) { i ->
            MediaItem.Builder()
                .setMediaId("fake-media-$i")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Fake track $i")
                        .setArtist("Fake artist")
                        .build(),
                )
                .build()
        }
    }
}
