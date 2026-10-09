package com.pianostream.core.media.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.common.audio.AudioProcessor
import com.pianostream.core.media.audio.ReplayGainAudioProcessor
import com.pianostream.core.media.audiofx.MonoAudioProcessor
import com.pianostream.core.media.audiofx.NormalizeGainProcessor
import com.pianostream.core.media.audiofx.PlaybackPrefsApplier
import com.pianostream.core.media.cache.MediaCache
import com.pianostream.core.media.datasource.SchemeRoutingDataSource
import com.pianostream.core.media.datasource.StreamResolvingDataSource
import com.pianostream.core.media.datasource.ContentIdCacheKeyFactory
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

/**
 * Player wiring (Phase 2 steps 2–5):
 *
 *  - Primary is built through `DefaultPreloadManager.Builder.buildExoPlayer`
 *    so player + the ONE manager share media-source factory and `SimpleCache`
 *    (M5).
 *  - The crossfade secondary uses a separate builder: `handleAudioFocus=false`,
 *    `handleAudioBecomingNoisy=false`, `setAudioSessionId(primary)` and its OWN
 *    `ReplayGainAudioProcessor` (H3/H4).
 *  - `replayGainMode = OFF` ⇒ no processor installed at all (RenderersFactory
 *    is frozen at service start — a restart is required).
 *  - playbackfx: every sink also carries a `NormalizeGainProcessor` and a
 *    `MonoAudioProcessor` (fresh instances per sink — processor state is not
 *    shareable). They read `PlaybackFxState.shared`, which
 *    `PlaybackPrefsApplier.ensureStarted` feeds from SettingsDataStore — so
 *    `monoAudio`/`normalizeVolume`/`preampDb` apply without any DI edits.
 */
object PlayerFactory {

    /** Builds the shared data-source stack: Cache → Resolving → upstream. */
    fun dataSourceFactory(
        context: Context,
        resolver: com.pianostream.core.youtube.api.StreamResolver,
        preferredItag: Int,
        localResolver: suspend (String) -> String?,
        okHttp: OkHttpClient,
        cacheBudgetBytes: Long,
        videoItagProvider: () -> Int? = { null },
    ): DataSource.Factory {
        val upstreamHttp = OkHttpDataSource.Factory(okHttp.newBuilder().build())
        val localUpstream = DefaultDataSource.Factory(context)
        val resolving = StreamResolvingDataSource.Factory(
            resolver = resolver,
            upstreamFactory = { upstreamHttp.createDataSource() },
            preferredItag = preferredItag,
            localResolver = localResolver,
            videoItagProvider = videoItagProvider,
        )
        val cached = CacheDataSource.Factory()
            .setCache(MediaCache.getInstance(context, cacheBudgetBytes))
            .setCacheKeyFactory(ContentIdCacheKeyFactory())
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Local keys route around the cache entirely (local FLAC is never
        // written to the streaming cache).
        val uncached = StreamResolvingDataSource.Factory(
            resolver = resolver,
            upstreamFactory = { localUpstream.createDataSource() },
            preferredItag = preferredItag,
            localResolver = localResolver,
            videoItagProvider = videoItagProvider,
        )
        return DataSource.Factory {
            SchemeRoutingDataSource(
                cached = cached.createDataSource(),
                uncached = uncached.createDataSource(),
            )
        }
    }

    /**
     * The playbackfx stage set for one sink: normalize/preamp then mono
     * downmix (downmix last — fewer channels to scale). Every player gets
     * fresh instances; they all read `PlaybackFxState.shared`.
     */
    fun defaultAudioFxProcessors(): List<AudioProcessor> =
        listOf(NormalizeGainProcessor(), MonoAudioProcessor())

    /** RenderersFactory with the ReplayGain processor installed (unless OFF). */
    fun renderersFactory(
        context: Context,
        replayGainProcessor: ReplayGainAudioProcessor?,
        audioFxProcessors: List<AudioProcessor> = defaultAudioFxProcessors(),
    ): RenderersFactory {
        // Self-wired prefs collector — MediaModule is frozen, so the first
        // factory build launches the one process-wide DataStore collect.
        PlaybackPrefsApplier.ensureStarted(context)
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                val processors = listOfNotNull(replayGainProcessor) + audioFxProcessors
                if (processors.isNotEmpty()) {
                    builder.setAudioProcessors(processors.toTypedArray())
                }
                return builder.build()
            }
        }
    }

    /** The primary player — via the shared preload-manager builder (M5). */
    fun buildPrimary(
        context: Context,
        preloadManagerBuilder: DefaultPreloadManager.Builder,
        renderersFactory: RenderersFactory,
        loadControl: androidx.media3.exoplayer.LoadControl,
    ): ExoPlayer {
        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioOffloadPolicy.primaryAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
        return preloadManagerBuilder.buildExoPlayer(builder)
    }

    /** The crossfade secondary — H4 config, own processors, no focus. */
    fun buildSecondary(
        context: Context,
        primaryAudioSessionId: Int,
        mediaSourceFactory: MediaSource.Factory,
        replayGainProcessor: ReplayGainAudioProcessor?,
        audioFxProcessors: List<AudioProcessor> = defaultAudioFxProcessors(),
    ): ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            renderersFactory(context, replayGainProcessor, audioFxProcessors),
        )
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(AudioOffloadPolicy.primaryAttributes(), false)
        .setHandleAudioBecomingNoisy(false)
        .setWakeMode(C.WAKE_MODE_NONE)
        .build()
        .also { it.setAudioSessionId(primaryAudioSessionId) }
}
