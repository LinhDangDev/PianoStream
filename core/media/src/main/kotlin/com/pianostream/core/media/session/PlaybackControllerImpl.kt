package com.pianostream.core.media.session

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.PlaybackRepositoryImpl
import com.pianostream.core.media.PlaybackService
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.media.queue.QueueAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * App-scoped `MediaController` holder + the explicit cold-start queue restore
 * (M9): when the connected player has an empty timeline AND `playback.db`
 * holds a saved queue, the assembled per-track items are restored via
 * `setMediaItems(items, index, positionMs)` BEFORE `isPlaylistReady` flips —
 * the queue sheet stays inert on an empty timeline (M9/C1).
 */
class PlaybackControllerImpl(
    private val context: Context,
    private val repository: PlaybackRepository,
    private val assembler: QueueAssembler,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : PlaybackController {

    private var controller: MediaController? = null

    private val ready = MutableStateFlow(false)
    override val isPlaylistReady: Flow<Boolean> = ready

    override val player: Player
        get() = controller ?: error("PlaybackController not connected")

    override suspend fun connect(): Boolean {
        val built = suspendCancellableCoroutine<MediaController?> { cont ->
            val future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync()
            future.addListener(
                { cont.resume(runCatching { future.get() }.getOrNull()) },
                MoreExecutors.directExecutor(),
            )
            cont.invokeOnCancellation { future.cancel(true) }
        } ?: return false
        controller = built

        // Ready flips whenever the timeline gains items — cold-start restore
        // below OR a later UI-submitted setMediaItems (AppViewModel.playQueue).
        built.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (built.mediaItemCount > 0) ready.value = true
            }
        })

        // M9: explicit restore on the connect path (NOT onPlaybackResumption).
        val restored = restoreColdStart(built)
        ready.value = built.mediaItemCount > 0 || restored
        return true
    }

    /**
     * Restores `queue_items` + `queue_state` into an empty timeline. Returns
     * true when anything was restored.
     */
    private suspend fun restoreColdStart(player: MediaController): Boolean {
        if (player.mediaItemCount != 0) return false
        val impl = repository as? PlaybackRepositoryImpl ?: return false
        if (!impl.hasSavedQueue()) return false
        val rows = impl.queueSnapshot()
        val cursor = impl.queueCursor()
        val assembled = assembler.assemble(rows)
        if (assembled.items.isEmpty()) return false
        val index = (cursor?.currentIndex ?: 0).coerceIn(0, assembled.items.size - 1)
        player.setMediaItems(
            assembled.items,
            index,
            cursor?.positionMs ?: 0L,
        )
        player.prepare()
        return true
    }

    override fun release() {
        controller?.release()
        controller = null
        ready.value = false
    }
}
