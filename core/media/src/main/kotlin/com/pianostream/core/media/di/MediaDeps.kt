package com.pianostream.core.media.di

import androidx.media3.exoplayer.ExoPlayer
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.media.crossfade.CrossfadeController
import com.pianostream.core.media.preload.PreloadPolicy
import com.pianostream.core.media.queue.QueueStateWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Injectable holder for everything `PlaybackService` needs — built by
 * `MediaModule`, so the service stays a thin lifecycle shim (M5: the primary
 * player comes from the single `DefaultPreloadManager` builder; the
 * secondary is lazily created by the crossfade controller only).
 */
@Singleton
class MediaDeps @Inject constructor(
    val primaryPlayer: ExoPlayer,
    val crossfade: CrossfadeController,
    val queueStateWriter: QueueStateWriter,
    val settings: SettingsDataStore,
    val preloadPolicy: PreloadPolicy,
) {

    @Volatile
    private var secondary: ExoPlayer? = null

    /** Lazily creates the crossfade secondary (H4 config — built by the module's factory lambda). */
    var secondaryFactory: (() -> ExoPlayer)? = null

    fun secondaryPlayer(): ExoPlayer =
        secondary ?: secondaryFactory!!().also { secondary = it }

    fun releaseSecondary() {
        runCatching { secondary?.release() }
        secondary = null
    }

    /** Lets the service share its scope with the controller/writer. */
    fun attachServiceScope(scope: CoroutineScope) {
        // QueueStateWriter ticks on its own scope already; this hook exists so
        // service-owned coroutines (future restore work) join the service
        // lifecycle.
    }
}
