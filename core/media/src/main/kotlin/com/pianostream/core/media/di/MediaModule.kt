package com.pianostream.core.media.di

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.local.api.LocalLibraryIndex
import com.pianostream.core.local.api.LocalTrackWindowSource
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.media.audio.ReplayGainAudioProcessor
import com.pianostream.core.media.cache.MediaCache
import com.pianostream.core.media.crossfade.CrossfadeController
import com.pianostream.core.media.local.LocalMediaItemFactory
import com.pianostream.core.media.player.PlayerFactory
import com.pianostream.core.media.preload.PreloadPolicy
import com.pianostream.core.media.queue.QueueAssembler
import com.pianostream.core.media.queue.QueueStateWriter
import com.pianostream.core.media.session.PlaybackControllerImpl
import com.pianostream.core.youtube.api.StreamResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Phase 2 wiring — players, cache, session deps, controller. */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun mediaScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Provides
    @Singleton
    fun mediaSourceFactory(
        @ApplicationContext context: Context,
        resolver: StreamResolver,
        localWindowSource: LocalTrackWindowSource,
        settings: SettingsDataStore,
        okHttp: OkHttpClient,
    ): DefaultMediaSourceFactory {
        val budget = runBlocking { settings.cacheBudgetBytes.first() }
        val itag = runBlocking { settings.preferredItag.first() }
        val dsFactory = PlayerFactory.dataSourceFactory(
            context = context,
            resolver = resolver,
            preferredItag = itag,
            localResolver = { key ->
                localWindowSource.windows(listOf(key)).windows
                    .firstOrNull()?.flacUri
            },
            okHttp = okHttp,
            cacheBudgetBytes = budget,
        )
        return DefaultMediaSourceFactory(context).setDataSourceFactory(dsFactory)
    }

    @Provides
    @Singleton
    fun preloadManagerBuilder(
        @ApplicationContext context: Context,
        mediaSourceFactory: DefaultMediaSourceFactory,
        settings: SettingsDataStore,
        preloadPolicy: PreloadPolicy,
    ): DefaultPreloadManager.Builder {
        val budget = runBlocking { settings.cacheBudgetBytes.first() }
        return DefaultPreloadManager.Builder(context, preloadPolicy)
            .setMediaSourceFactory(mediaSourceFactory)
            .setCache(MediaCache.getInstance(context, budget))
    }

    @Provides
    @Singleton
    fun preloadPolicy(): PreloadPolicy = PreloadPolicy()

    @Provides
    @Singleton
    fun replayGainProcessor(settings: SettingsDataStore): ReplayGainAudioProcessor? {
        // replayGainMode = OFF ⇒ the processor is NOT installed at all
        // (RenderersFactory is frozen at service start; restart required).
        val mode = runBlocking { settings.replayGainMode.first() }
        return if (mode == SettingsDataStore.ReplayGainMode.OFF) null
        else ReplayGainAudioProcessor()
    }

    @Provides
    @Singleton
    fun primaryPlayer(
        @ApplicationContext context: Context,
        preloadManagerBuilder: DefaultPreloadManager.Builder,
        replayGainProcessor: ReplayGainAudioProcessor?,
    ): ExoPlayer = PlayerFactory.buildPrimary(
        context = context,
        preloadManagerBuilder = preloadManagerBuilder,
        renderersFactory = PlayerFactory.renderersFactory(context, replayGainProcessor),
        loadControl = DefaultLoadControl(),
    )

    @Provides
    @Singleton
    fun secondaryGainProcessor(): ReplayGainAudioProcessor =
        // H3 — the crossfade secondary always carries its OWN processor
        // instance (independent of the primary's mode: a fade may happen
        // between two tracks when mode is ON; when OFF neither exists).
        ReplayGainAudioProcessor()

    @Provides
    @Singleton
    fun crossfadeController(
        @ApplicationContext context: Context,
        mediaSourceFactory: DefaultMediaSourceFactory,
        settings: SettingsDataStore,
        primary: ExoPlayer,
        mediaScope: CoroutineScope,
        secondaryGain: ReplayGainAudioProcessor,
    ): CrossfadeController {
        val secondaryProvider: () -> ExoPlayer = {
            PlayerFactory.buildSecondary(
                context = context,
                primaryAudioSessionId = primary.audioSessionId,
                mediaSourceFactory = mediaSourceFactory,
                replayGainProcessor = secondaryGain,
            )
        }
        return CrossfadeController(
            secondaryPlayer = secondaryProvider,
            primary = primary,
            scope = mediaScope,
            setSecondaryGain = { db, peak ->
                secondaryGain.gain = com.pianostream.core.media.audio.GainCalculator
                    .linearGain(db, peak, runBlocking { settings.preampDb.first() })
            },
            crossfadeMsProvider = { runBlocking { settings.crossfadeMs.first() } },
        )
    }

    @Provides
    @Singleton
    fun localMediaItemFactory(@ApplicationContext context: Context): LocalMediaItemFactory =
        LocalMediaItemFactory(context)

    @Provides
    @Singleton
    fun queueAssembler(
        localItems: LocalMediaItemFactory,
        windowSource: LocalTrackWindowSource,
        libraryIndex: LocalLibraryIndex,
    ): QueueAssembler = QueueAssembler(localItems, windowSource) { key ->
        libraryIndex.index()[key]?.let {
            QueueAssembler.DiscArtwork(it.discKey, it.artworkUri, it.flacDurationMs)
        }
    }

    @Provides
    @Singleton
    fun queueStateWriter(
        mediaScope: CoroutineScope,
        primary: ExoPlayer,
        repository: PlaybackRepository,
    ): QueueStateWriter = QueueStateWriter(
        scope = mediaScope,
        snapshot = {
            if (primary.mediaItemCount == 0) null
            else com.pianostream.core.data.playback.model.QueueState(
                currentIndex = primary.currentMediaItemIndex,
                positionMs = primary.currentPosition,
                repeatMode = primary.repeatMode,
                shuffle = primary.shuffleModeEnabled,
                updatedAt = System.currentTimeMillis(),
            )
        },
        write = { repository.saveQueueCursor(it) },
    )

    @Provides
    @Singleton
    fun playbackController(
        @ApplicationContext context: Context,
        repository: PlaybackRepository,
        assembler: QueueAssembler,
    ): PlaybackController = PlaybackControllerImpl(context, repository, assembler)

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
