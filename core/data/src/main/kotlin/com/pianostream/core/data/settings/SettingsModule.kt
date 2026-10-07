package com.pianostream.core.data.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Shared DataStore wiring (Phase 2). */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): SettingsDataStore =
        SettingsDataStore.default(context)
}
