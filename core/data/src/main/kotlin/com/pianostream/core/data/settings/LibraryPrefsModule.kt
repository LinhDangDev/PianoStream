package com.pianostream.core.data.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** `pianostream.library` DataStore wiring (library feature set). */
@Module
@InstallIn(SingletonComponent::class)
object LibraryPrefsModule {

    @Provides
    @Singleton
    fun libraryPrefsStore(@ApplicationContext context: Context): LibraryPrefsStore =
        LibraryPrefsStore.default(context)
}
