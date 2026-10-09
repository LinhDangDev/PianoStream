package com.pianostream.core.youtube.di

import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.spotify.SpotifyImportRunner
import com.pianostream.core.youtube.spotify.SpotifyPlaylistImporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Spotify playlist import wiring — kept in its own module so the frozen
 * [YouTubeModule] stays untouched.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object SpotifyImportModule {

    @Provides
    @Singleton
    fun spotifyPlaylistImporter(): SpotifyPlaylistImporter = SpotifyPlaylistImporter()

    @Provides
    @Singleton
    fun spotifyImportRunner(youTubeClient: YouTubeClient): SpotifyImportRunner =
        SpotifyImportRunner(youTubeClient)
}
