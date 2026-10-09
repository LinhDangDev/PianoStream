package com.pianostream.core.data.downloads

import com.pianostream.core.data.playback.db.PlaybackDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** `downloads` store wiring — the impl is provided here; the interface
 *  binding lives in the app flavor sets (same seam as PlaybackRepository). */
@Module
@InstallIn(SingletonComponent::class)
public object DownloadsModule {

    @Provides
    @Singleton
    public fun downloadRepositoryImpl(db: PlaybackDatabase): DownloadRepositoryImpl =
        DownloadRepositoryImpl(db)
}
