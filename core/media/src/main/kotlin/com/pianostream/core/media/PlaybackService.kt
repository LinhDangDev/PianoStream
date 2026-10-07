package com.pianostream.core.media

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.settings.SettingsDataStore.TaskRemovalPolicy
import com.pianostream.core.media.audio.ReplayGainAudioProcessor
import com.pianostream.core.media.crossfade.CrossfadeController
import com.pianostream.core.media.di.MediaDeps
import com.pianostream.core.media.queue.QueueStateWriter
import com.pianostream.core.media.session.MediaLibrarySessionCallback
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single `MediaLibraryService` (Phase 2): one ExoPlayer built in
 * `onCreate`/released in `onDestroy`, one `MediaLibrarySession`, and the
 * CC9-guarded callback. Never calls `startForeground` manually, never
 * auto-resumes at boot; FGS-start failures are caught and surfaced via the
 * player error listener (M6). `onTaskRemoved` reads the frozen
 * `taskRemovalPolicy` — `STOP` (default) pauses all players and stops self;
 * `PAUSE` pauses only (C4).
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var playerFactoryDeps: MediaDeps

    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val deps = playerFactoryDeps
        val player = deps.primaryPlayer
        session = MediaLibrarySession.Builder(
            this,
            player,
            MediaLibrarySessionCallback(this),
        )
            .setId("pianostream-playback")
            .build()
        player.addListener(deps.queueStateWriter.playerListener)
        // Feed the playing index into the preload policy so the manager
        // targets next-track preload (3 s) rather than index 0.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                deps.preloadPolicy.onUsedByPlayer(player.currentMediaItemIndex)
            }
        })
        deps.attachServiceScope(serviceScope)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val policy = runBlocking {
            playerFactoryDeps.settings.taskRemovalPolicy.first()
        }
        when (policy) {
            TaskRemovalPolicy.PAUSE -> {
                playerFactoryDeps.primaryPlayer.pause()
                playerFactoryDeps.queueStateWriter.persistBlocking()
                // Service stays for quick resume — super NOT called.
            }
            TaskRemovalPolicy.STOP -> {
                // "Swipe = stop" (C4): write the cursor then tear down.
                playerFactoryDeps.queueStateWriter.persistBlocking()
                playerFactoryDeps.crossfade.abort()
                pauseAllPlayersAndStopSelf()
            }
        }
    }

    override fun onDestroy() {
        val deps = playerFactoryDeps
        deps.queueStateWriter.stop()
        deps.crossfade.abort()
        session?.release()
        session = null
        deps.primaryPlayer.release()
        deps.releaseSecondary()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** M6 — FGS-start failure surfaces through the error reporter, not a crash. */
    internal fun reportFgsDenied(t: Throwable) {
        android.util.Log.w(TAG, "mediaPlayback FGS start denied", t)
    }

    companion object {
        private const val TAG = "PlaybackService"
    }
}
