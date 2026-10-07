package com.pianostream.app.fake

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Deterministic in-memory `Player` for the `stub` flavor (SimpleBasePlayer):
 * a fixed queue plus a ~1 Hz invalidate ticker while playing — the plan's
 * "fake advancing player". No service, no Surface, no audio.
 */
@UnstableApi
public class FakePlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var ticker: Job? = null
    private var positionMs: Long = 0L

    private var _state: State = State.Builder()
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
        .setPlaylist(FAKE_MEDIA_DATA)
        .setPlaylistMetadata(MediaMetadata.EMPTY)
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()

    override fun getState(): State = _state

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                positionMs += 1_000
                invalidateState()
            }
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) startTicker() else ticker?.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        ticker?.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        this.positionMs = positionMs.coerceAtLeast(0)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        ticker?.cancel()
        return Futures.immediateVoidFuture()
    }

    public companion object {
        private val FAKE_MEDIA_DATA: List<MediaItemData> = (1..3).map { i ->
            MediaItemData.Builder("fake-uid-$i")
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("pianostream://local/fake-key-$i")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Fake track $i")
                                .setArtist("Fake performer")
                                .build(),
                        )
                        .build(),
                )
                .setDurationUs(210_000_000L)
                .setIsSeekable(true)
                .build()
        }
    }
}
