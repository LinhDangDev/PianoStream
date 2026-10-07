package com.pianostream.core.data.library

import android.content.Context
import androidx.room.Room
import com.pianostream.core.data.library.db.LibraryDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the `library.db` owner + provides the database (Phase 3). */
@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryModule {
    @Binds
    @Singleton
    abstract fun libraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    companion object {
        @Provides
        @Singleton
        fun libraryDatabase(@ApplicationContext context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
                .build()
    }
}
