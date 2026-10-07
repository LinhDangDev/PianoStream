package com.pianostream.core.data.playback

import android.content.Context
import androidx.room.Room
import com.pianostream.core.data.playback.db.PlaybackDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** `playback.db` + repository wiring (Phase 2). */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun playbackDatabase(@ApplicationContext context: Context): PlaybackDatabase =
        Room.databaseBuilder(context, PlaybackDatabase::class.java, PlaybackDatabase.NAME)
            // No destructive fallback — deleteAllLocalData() is the only wipe
            // path (CC10); a schema mismatch surfaces loudly, never silent loss.
            .build()

    @Provides
    @Singleton
    fun playbackRepository(db: PlaybackDatabase): PlaybackRepository =
        PlaybackRepositoryImpl(db)
}
