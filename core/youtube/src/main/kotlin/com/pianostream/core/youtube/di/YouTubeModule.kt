package com.pianostream.core.youtube.di

import android.content.Context
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.youtube.api.StreamResolver
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.cache.LruStreamUrlCache
import com.pianostream.core.youtube.cache.StreamUrlCache
import com.pianostream.core.youtube.cipher.CipherSolver
import com.pianostream.core.youtube.cipher.PlayerJsFetcher
import com.pianostream.core.youtube.cipher.QuickJsCipherSolver
import com.pianostream.core.youtube.cipher.RemotePlayerConfigStore
import com.pianostream.core.youtube.cipher.WebViewCipherSolver
import com.pianostream.core.youtube.client.ClientFallbackStrategy
import com.pianostream.core.youtube.client.ClientHealthTracker
import com.pianostream.core.youtube.innertube.InnerTubeClientImpl
import com.pianostream.core.youtube.player.StreamResolverImpl
import com.pianostream.core.youtube.potoken.PoTokenCache
import com.pianostream.core.youtube.potoken.PoTokenProvider
import com.pianostream.core.youtube.potoken.PoTokenProviderImpl
import com.pianostream.core.youtube.potoken.WebViewPoTokenMinter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

/**
 * Wiring for `:core:youtube` — binds the frozen interfaces to their impls.
 * The DELETE ALL LOCAL DATA hook ([wipeLocal]) is called by `:app`'s
 * clear-all path: poToken caches + persisted player config zeroed (CC10).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object YouTubeModule {

    /** Dedicated single-thread dispatcher for bounded JS eval (F14). */
    @Provides
    @Singleton
    @Named("cipherDispatcher")
    fun cipherDispatcher(): CoroutineDispatcher =
        @OptIn(DelicateCoroutinesApi::class)
        newSingleThreadContext("pianostream-cipher")

    @Provides
    @Singleton
    fun streamUrlCache(): StreamUrlCache = LruStreamUrlCache()

    @Provides
    @Singleton
    fun clientHealthTracker(): ClientHealthTracker = ClientHealthTracker()

    @Provides
    @Singleton
    fun clientFallbackStrategy(health: ClientHealthTracker): ClientFallbackStrategy =
        ClientFallbackStrategy(health)

    @Provides
    @Singleton
    fun playerJsFetcher(): PlayerJsFetcher = PlayerJsFetcher()

    @Provides
    @Singleton
    fun poTokenCache(): PoTokenCache = PoTokenCache()

    @Provides
    @Singleton
    fun poTokenProvider(
        @ApplicationContext context: Context,
        cache: PoTokenCache,
    ): PoTokenProvider = PoTokenProviderImpl(WebViewPoTokenMinter(context), cache)

    @Provides
    @Singleton
    fun remotePlayerConfigStore(
        @ApplicationContext context: Context,
        settings: SettingsDataStore,
    ): RemotePlayerConfigStore = RemotePlayerConfigStore(
        seedJson = context.assets.open("player_configs_seed.json").use { it.readBytes() },
        filesDir = context.filesDir,
        enabledProvider = {
            runBlocking(Dispatchers.IO) { settings.remoteConfigEnabled.first() }
        },
    )

    @Provides
    @Singleton
    fun quickJsCipherSolver(
        @Named("cipherDispatcher") dispatcher: CoroutineDispatcher,
    ): QuickJsCipherSolver = QuickJsCipherSolver(dispatcher)

    @Provides
    @Singleton
    fun webViewCipherSolver(
        @ApplicationContext context: Context,
        @Named("cipherDispatcher") dispatcher: CoroutineDispatcher,
    ): WebViewCipherSolver = WebViewCipherSolver(context, dispatcher)

    @Provides
    @Singleton
    fun streamResolver(
        health: ClientHealthTracker,
        fallback: ClientFallbackStrategy,
        quickJs: QuickJsCipherSolver,
        webView: WebViewCipherSolver,
        poToken: PoTokenProvider,
        cache: StreamUrlCache,
        configStore: RemotePlayerConfigStore,
        playerJs: PlayerJsFetcher,
    ): StreamResolver = StreamResolverImpl(
        health = health,
        fallback = fallback,
        cipherSolver = quickJs,
        fallbackSolver = webView,
        poToken = poToken,
        cache = cache,
        configStore = configStore,
        playerJs = playerJs,
    )

    @Provides
    @Singleton
    fun youTubeClient(): YouTubeClient = InnerTubeClientImpl()

    /** CC10 hook for the app's DELETE ALL LOCAL DATA path (Phase 4 calls). */
    @Provides
    @Singleton
    fun wipeHook(
        poToken: PoTokenProvider,
        configStore: RemotePlayerConfigStore,
    ): YouTubeLocalWipe = YouTubeLocalWipe {
        poToken.wipe()
        configStore.wipe()
    }
}

/** Value-handle for the CC10 wipe hook (kept injectable for tests).
 * Public: the Phase-4 app Settings DELETE-ALL path injects it. */

public fun interface YouTubeLocalWipe {
    fun wipe()
}
