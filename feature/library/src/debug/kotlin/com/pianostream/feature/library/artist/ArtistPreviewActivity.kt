package com.pianostream.feature.library.artist

import android.app.Activity
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.ArtistTrack
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.designsystem.theme.PianoStreamTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * DEBUG-ONLY host for artist-screen screenshots — the screen isn't wired to
 * nav yet (integrator's job), so this activity mounts it directly on a fake
 * repository. Launch: `adb shell am start -n
 * com.pianostream.debug/com.pianostream.feature.library.artist.ArtistPreviewActivity`
 * Add `--ei empty 1` for the empty state. Not part of any shipped flow.
 */
class ArtistPreviewActivity : Activity() {

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle = registry
        override val savedStateRegistry: SavedStateRegistry =
            savedState.savedStateRegistry

        fun resume() {
            // rememberSaveable inside LazyColumn needs an attached +
            // restored registry (it reads via LocalSaveableStateRegistry).
            savedState.performAttach()
            savedState.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private val owner = Owner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val empty = intent.getBooleanExtra("empty", false)
        val vm = ArtistViewModel(if (empty) EmptyRepo() else FakeRepo)
        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        setContentView(view)
        owner.resume()
        view.setContent {
            PianoStreamTheme(dynamicColor = false) {
                ArtistScreen(
                    artistName = "Daniel Barenboim",
                    onBack = { finish() },
                    onPlayTracks = { _, _ -> },
                    onOpenDisc = {},
                    viewModel = vm,
                )
            }
        }
    }

    private class EmptyRepo : FakeBase()
    private object FakeRepo : FakeBase() {
        override val artistDiscs = MutableStateFlow(
            listOf(
                disc(1, "Symphonies Nos. 5 & 7", single = false, art = "a1"),
                disc(2, "Moonlight Sonata (loose)", single = true, art = null),
            ),
        )
        override val artistTracks = MutableStateFlow(
            listOf(
                row(10, 1, 1, "Symphony No. 5 in C minor, Op. 67: I. Allegro con brio", 430_000),
                row(11, 1, 2, "Symphony No. 5 in C minor, Op. 67: II. Andante con moto", 560_000),
                row(20, 2, 1, "Moonlight Sonata (loose)", 330_000),
            ),
        )
    }

    private open class FakeBase : LibraryRepository {
        open val artistDiscs = MutableStateFlow<List<Disc>>(emptyList())
        open val artistTracks = MutableStateFlow<List<ArtistTrack>>(emptyList())

        override fun observeArtistDiscs(artist: String): Flow<List<Disc>> = artistDiscs
        override fun observeArtistTracks(artist: String): Flow<List<ArtistTrack>> = artistTracks

        override val discs: Flow<List<Disc>> = flowOf(emptyList())
        override fun observeTracks(discId: Long): Flow<List<Track>> = flowOf(emptyList())
        override suspend fun discById(id: Long): Disc? = null
        override suspend fun tracksOf(discId: Long): List<Track> = emptyList()
        override fun search(query: String): Flow<List<SearchHit>> = flowOf(emptyList())
        override suspend fun distinctComposers(): List<String> = emptyList()
        override val scanProgress: Flow<ScanProgress> = flowOf(ScanProgress(0, null))
        override suspend fun startScan(): ScanResult = ScanResult(0, 0, 0, 0)
        override suspend fun revokeLibraryAccess() {}
        override suspend fun clearAll() {}
    }

    private companion object {
        fun disc(id: Long, title: String, single: Boolean, art: String?) = Disc(
            id = id, docId = "d$id", treeUri = "t", folderPath = "music/f$id",
            title = title, performer = "Daniel Barenboim", composer = null,
            genre = null, date = null, discId = null, discNumber = null,
            totalDiscs = null, cueUri = if (single) null else "cue$id",
            flacUri = "flac$id", logUri = null, artworkUri = art,
            albumGainDb = null, albumPeak = null, flacDurationMs = 3_600_000,
            flacSizeBytes = 0, flacLastModified = null, scannedAt = 0L,
        )

        fun row(id: Long, discId: Long, no: Int, title: String, durMs: Long) = ArtistTrack(
            track = Track(
                id = id, discId = discId, trackNo = no, title = title,
                performer = "Daniel Barenboim", composer = null,
                startMs = 0, endMs = durMs, pregapStartMs = null,
                gainDb = null, peak = null, isrc = null, flags = 0,
            ),
            discTitle = if (discId == 2L) title else "Symphonies Nos. 5 & 7",
            discFolderPath = "music/f$discId",
            discArtworkUri = "a$discId",
            indexInCue = no - 1,
        )
    }
}
