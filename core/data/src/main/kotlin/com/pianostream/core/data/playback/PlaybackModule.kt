package com.pianostream.core.data.playback

import android.content.Context
import androidx.room.Room
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.PlaybackMigrations
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
            .addMigrations(PlaybackMigrations.MIGRATION_1_2)
            .build()

    // The interface binding lives in the app flavor sets (stub fakes / real
    // impls) per the plan's stub↔real seam; the impl itself is provided here
    // because its lambdas have Kotlin defaults Dagger cannot see.
    @Provides
    @Singleton
    fun playbackRepositoryImpl(
        db: PlaybackDatabase,
        streamCacheEvictor: StreamCacheEvictor,
    ): PlaybackRepositoryImpl =
        PlaybackRepositoryImpl(db, streamCacheEvictor = streamCacheEvictor)
}
