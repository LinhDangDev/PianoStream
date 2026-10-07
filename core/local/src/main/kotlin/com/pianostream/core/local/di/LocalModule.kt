package com.pianostream.core.local.di

import com.pianostream.core.data.library.scan.LibraryScanDriver
import com.pianostream.core.local.api.LocalTrackWindowSource
import com.pianostream.core.local.api.LocalTrackWindowSourceImpl
import com.pianostream.core.local.scan.LibraryScanDriverImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** `core:local` wiring — scanner driver + window source (`library.db`
 * itself is provided by `core:data`'s `LibraryModule`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalModule {
    @Binds
    @Singleton
    abstract fun windowSource(impl: LocalTrackWindowSourceImpl): LocalTrackWindowSource

    @Binds
    @Singleton
    abstract fun scanDriver(impl: LibraryScanDriverImpl): LibraryScanDriver
}
