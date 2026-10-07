package com.pianostream.app.di

import com.pianostream.app.fake.FakeLibraryRepository
import com.pianostream.app.fake.FakePlaybackController
import com.pianostream.app.fake.FakePlaybackRepository
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.media.api.PlaybackController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `stub` flavor binding set — in-memory fakes for the interfaces the app
 * drives (plan's stub↔real seam; compile gate `assembleGithubStubDebug`).
 *
 * Deviations from the phase-04 fake list (documented in docs/progress-log):
 *  - `SettingsDataStore` is a final class per the Interface Freeze — it
 *    cannot be faked; the real DataStore-backed instance is used in both
 *    flavors.
 *  - `YouTubeClient`/`StreamResolver` impls are `internal` to `core:youtube`,
 *    so their bindings stay in `YouTubeModule`; the fakes exist for
 *    ViewModel/unit tests (incl. the F12 call-counting leak tests).
 *  - `MediaCatalog` does not exist as a frozen interface — no fake needed.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class StubBindings {

    @Binds
    @Singleton
    public abstract fun playbackRepository(
        impl: FakePlaybackRepository,
    ): PlaybackRepository

    @Binds
    @Singleton
    public abstract fun libraryRepository(
        impl: FakeLibraryRepository,
    ): LibraryRepository

    @Binds
    @Singleton
    public abstract fun playbackController(
        impl: FakePlaybackController,
    ): PlaybackController
}
