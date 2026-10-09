package com.pianostream.feature.library.artist

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.ArtistTrack
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.local.scan.LibraryHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistViewModelTest {

    private class FakeLibraryRepo : LibraryRepository {
        val artistDiscs = MutableStateFlow<List<Disc>>(emptyList())
        val artistTracks = MutableStateFlow<List<ArtistTrack>>(emptyList())
        var lastArtist: String? = null

        override fun observeArtistDiscs(artist: String): Flow<List<Disc>> {
            lastArtist = artist
            return artistDiscs
        }
        override fun observeArtistTracks(artist: String): Flow<List<ArtistTrack>> =
            artistTracks

        override val discs: Flow<List<Disc>> = flowOf(emptyList())
        override fun observeTracks(discId: Long): Flow<List<Track>> =
            flowOf(emptyList())
        override suspend fun discById(id: Long): Disc? = null
        override suspend fun tracksOf(discId: Long): List<Track> = emptyList()
        override fun search(query: String): Flow<List<SearchHit>> = flowOf(emptyList())
        override suspend fun distinctComposers(): List<String> = emptyList()
        override val scanProgress: Flow<ScanProgress> = flowOf(ScanProgress(0, null))
        override suspend fun startScan(): ScanResult = ScanResult(0, 0, 0, 0)
        override suspend fun revokeLibraryAccess() {}
        override suspend fun clearAll() {}
    }

    private var mainDispatcher: TestDispatcher? = null

    private fun TestScope.vm(): ArtistViewModel {
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher!!)
        return ArtistViewModel(FakeLibraryRepo()).also {
            it.load("artist")
            advanceUntilIdle()
        }
    }

    @After
    fun tearDown() {
        // No resetMain — suspended collectors make reset throw (same as
        // LibraryViewModelTest's teardown).
    }

    private fun disc(i: Int, folder: String = "f$i") = Disc(
        id = i.toLong(), docId = "d$i", treeUri = "t", folderPath = folder,
        title = "Disc $i", performer = "artist", composer = null, genre = null,
        date = null, discId = null, discNumber = null, totalDiscs = null,
        cueUri = "cue$i", flacUri = "u$i", logUri = null, artworkUri = null,
        albumGainDb = null, albumPeak = null, flacDurationMs = null,
        flacSizeBytes = 0, flacLastModified = null, scannedAt = 0L,
    )

    private fun track(id: Long, discId: Long, no: Int, endMs: Long? = 1_000) = Track(
        id = id, discId = discId, trackNo = no, title = "T$no",
        performer = "artist", composer = null, startMs = 0, endMs = endMs,
        pregapStartMs = null, gainDb = null, peak = null, isrc = null, flags = 0,
    )

    private fun row(t: Track, disc: Disc, cue: Int) = ArtistTrack(
        track = t, discTitle = disc.title, discFolderPath = disc.folderPath,
        discArtworkUri = disc.artworkUri, indexInCue = cue,
    )

    @Test
    fun `load binds artist and combines discs plus tracks`() = runTest {
        val repo = FakeLibraryRepo()
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher!!)
        val vm = ArtistViewModel(repo)
        repo.artistDiscs.value = listOf(disc(1))
        repo.artistTracks.value = listOf(
            row(track(10, 1, 1), disc(1), cue = 0),
            row(track(11, 1, 2), disc(1), cue = 1),
        )
        vm.load("artist")
        advanceUntilIdle()
        assertThat(repo.lastArtist).isEqualTo("artist")
        val s = vm.state.value
        assertThat(s.loaded).isTrue()
        assertThat(s.discs).hasSize(1)
        assertThat(s.tracks).hasSize(2)
        assertThat(s.tracks.map { it.id }).containsExactly(10L, 11L).inOrder()
        assertThat(s.totalDurationMs).isEqualTo(2_000L)
        assertThat(s.queue).hasSize(2)
    }

    @Test
    fun `queue rows resolve cue-ordinal mediaIds`() = runTest {
        val repo = FakeLibraryRepo()
        val vm = ArtistViewModel(repo)
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher!!)
        repo.artistTracks.value = listOf(
            // Second disc: track lands at cue ordinal 2, not its list index.
            row(track(10, 1, 1), disc(1, "alpha"), cue = 0),
            row(track(20, 2, 3), disc(2, "beta"), cue = 2),
        )
        vm.load("artist")
        advanceUntilIdle()
        val queue = vm.state.value.queue
        assertThat(queue.map { it.mediaId }).containsExactly(
            LibraryHasher.mediaId("alpha", 0),
            LibraryHasher.mediaId("beta", 2),
        ).inOrder()
        assertThat(queue.map { it.position }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `shuffle reseats positions and keeps every row`() = runTest {
        val repo = FakeLibraryRepo()
        val vm = ArtistViewModel(repo)
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher!!)
        repo.artistTracks.value = (1..5).map { i ->
            row(track(i.toLong(), 1, i), disc(1), cue = i - 1)
        }
        vm.load("artist")
        advanceUntilIdle()
        val ordered = vm.state.value.queue
        val (shuffled, start) = vm.shuffleAll()
        assertThat(start).isEqualTo(0)
        assertThat(shuffled.map { it.mediaId })
            .containsExactlyElementsIn(ordered.map { it.mediaId })
        // Positions reseated — QueueAssembler sorts by position.
        assertThat(shuffled.map { it.position }).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test
    fun `queueFrom taps the full list at the tapped index`() = runTest {
        val repo = FakeLibraryRepo()
        val vm = ArtistViewModel(repo)
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher!!)
        repo.artistTracks.value = (1..3).map { i ->
            row(track(i.toLong(), 1, i), disc(1), cue = i - 1)
        }
        vm.load("artist")
        advanceUntilIdle()
        val (items, start) = vm.queueFrom(2)
        assertThat(items).hasSize(3)
        assertThat(start).isEqualTo(2)
    }

    @Test
    fun `unknown artist stays empty`() = runTest {
        val vm = vm()
        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.discs).isEmpty()
        assertThat(vm.state.value.tracks).isEmpty()
        assertThat(vm.state.value.queue).isEmpty()
    }
}
