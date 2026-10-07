package com.pianostream.app.di

import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.LibraryRepositoryImpl
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.PlaybackRepositoryImpl
import com.pianostream.core.media.api.PlaybackController
import com.pianostream.core.media.session.PlaybackControllerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `real` flavor binding set — binds the Phase 2/3 implementations. Compile
 * gate `:app:compileGithubRealDebugKotlin` keeps fake/real drift loud (R9).
 * `YouTubeClient`/`StreamResolver` stay bound in `YouTubeModule` (internal
 * impls); `SettingsDataStore` in `SettingsModule` (concrete class).
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class RealBindings {

    @Binds
    @Singleton
    public abstract fun playbackRepository(
        impl: PlaybackRepositoryImpl,
    ): PlaybackRepository

    @Binds
    @Singleton
    public abstract fun libraryRepository(
        impl: LibraryRepositoryImpl,
    ): LibraryRepository

    @Binds
    @Singleton
    public abstract fun playbackController(
        impl: PlaybackControllerImpl,
    ): PlaybackController
}
