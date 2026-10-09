package com.pianostream.feature.library

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.Playlist
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import com.pianostream.core.data.settings.LibraryPrefsStore
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadRepository
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.media.downloads.DownloadManager
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {

    private class FakeLibraryRepo : LibraryRepository {
        val discsFlow = MutableStateFlow<List<Disc>>(emptyList())
        val tracksFlow = MutableStateFlow<Map<Long, List<Track>>>(emptyMap())
        val progressFlow = MutableStateFlow(ScanProgress(0, null))
        var nextScan: ScanResult = ScanResult(0, 0, 10, 0)
        var scanCalls = 0
        var clearCalls = 0
        val progressHistory = mutableListOf<ScanProgress>()

        override val discs: Flow<List<Disc>> = discsFlow
        override fun observeTracks(discId: Long): Flow<List<Track>> =
            kotlinx.coroutines.flow.flow { tracksFlow.collect { emit(it[discId].orEmpty()) } }
        override suspend fun discById(id: Long): Disc? = discsFlow.value.find { it.id == id }
        override suspend fun tracksOf(discId: Long): List<Track> = tracksFlow.value[discId].orEmpty()
        override fun search(query: String): Flow<List<SearchHit>> = flowOf(emptyList())
        override suspend fun distinctComposers(): List<String> =
            tracksFlow.value.values.flatten().mapNotNull { it.composer }.distinct().sorted()
        override val scanProgress: Flow<ScanProgress> = progressFlow
        override suspend fun startScan(): ScanResult {
            scanCalls++
            repeat(3) { i ->
                ScanProgress(i + 1, "folder$i").also {
                    progressHistory.add(it); progressFlow.value = it
                }
            }
            // Terminal marker: folder null ⇒ scan finished (driver emits
            // the same shape; the collector maps it to scanning=false).
            progressFlow.value = ScanProgress(nextScan.discCount, null)
            return nextScan
        }
        override suspend fun revokeLibraryAccess() {}
        override suspend fun clearAll() { clearCalls++; discsFlow.value = emptyList(); tracksFlow.value = emptyMap() }
    }

    private class FakePlaybackRepo : PlaybackRepository {
        val favs = MutableStateFlow<List<FavoriteItem>>(emptyList())
        val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())
        val playlistTracks = MutableStateFlow<Map<Long, List<QueueItem>>>(emptyMap())
        private var nextPlaylistId = 1L

        override fun observeQueue(): Flow<List<QueueItem>> = flowOf(emptyList())
        override fun observeQueueState(): Flow<QueueState?> = flowOf(null)
        override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {}
        override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {}
        override suspend fun saveQueueCursor(state: QueueState) {}
        override fun observeHistory(): Flow<List<PlayHistoryItem>> = flowOf(emptyList())
        override suspend fun appendHistory(item: PlayHistoryItem) {}
        override fun observeFavorites(): Flow<List<FavoriteItem>> = favs
        override suspend fun addFavorite(item: FavoriteItem) {}
        override suspend fun removeFavorite(mediaId: String) {}
        override suspend fun isFavorite(mediaId: String): Boolean = false
        override suspend fun reconcileMissing(existingMediaIds: Set<String>) {}
        override suspend fun clearStreamCache() {}
        override suspend fun clearAll() {}

        override fun observePlaylists(): Flow<List<Playlist>> = playlistsFlow

        override suspend fun createPlaylist(name: String): Long {
            val id = nextPlaylistId++
            playlistsFlow.value = playlistsFlow.value +
                Playlist(id, name.trim(), createdAt = id, trackCount = 0)
            return id
        }

        override suspend fun renamePlaylist(id: Long, name: String) {
            playlistsFlow.value = playlistsFlow.value.map {
                if (it.id == id) it.copy(name = name.trim()) else it
            }
        }

        override suspend fun deletePlaylist(id: Long) {
            playlistsFlow.value = playlistsFlow.value.filterNot { it.id == id }
            playlistTracks.value = playlistTracks.value - id
        }

        override fun observePlaylistTracks(playlistId: Long): Flow<List<QueueItem>> =
            kotlinx.coroutines.flow.flow {
                playlistTracks.collect { emit(it[playlistId].orEmpty()) }
            }

        override suspend fun addToPlaylist(playlistId: Long, item: QueueItem) {
            val current = playlistTracks.value[playlistId].orEmpty()
                .filterNot { it.mediaId == item.mediaId }
            playlistTracks.value = playlistTracks.value +
                (playlistId to current + item.copy(position = current.size))
        }

        override suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) {
            playlistTracks.value = playlistTracks.value +
                (playlistId to playlistTracks.value[playlistId].orEmpty()
                    .filterNot { it.mediaId == mediaId })
        }

        override suspend fun movePlaylistItem(
            playlistId: Long,
            fromPosition: Int,
            toPosition: Int,
        ) {
            val rows = playlistTracks.value[playlistId].orEmpty().toMutableList()
            if (fromPosition !in rows.indices || toPosition !in rows.indices) return
            val moved = rows.removeAt(fromPosition)
            rows.add(toPosition, moved)
            playlistTracks.value = playlistTracks.value +
                (playlistId to rows.mapIndexed { i, it -> it.copy(position = i) })
        }

        override fun observePlaylistsContaining(mediaId: String): Flow<List<Long>> =
            kotlinx.coroutines.flow.flow {
                playlistTracks.collect { map ->
                    emit(
                        map.filterValues { rows ->
                            rows.any { it.mediaId == mediaId }
                        }.keys.toList(),
                    )
                }
            }

        override suspend fun removeFromAllPlaylists(mediaId: String) {
            playlistTracks.value = playlistTracks.value.mapValues { (_, rows) ->
                rows.filterNot { it.mediaId == mediaId }
            }
        }

        override suspend fun updatePlaylistDetails(
            id: Long,
            name: String,
            description: String,
        ) {
            playlistsFlow.value = playlistsFlow.value.map {
                if (it.id == id) {
                    it.copy(name = name.trim(), description = description.trim())
                } else {
                    it
                }
            }
        }
    }

    private lateinit var settings: SettingsDataStore
    private lateinit var libraryPrefs: LibraryPrefsStore
    private lateinit var library: FakeLibraryRepo
    private lateinit var playback: FakePlaybackRepo
    private var mainDispatcher: kotlinx.coroutines.test.TestDispatcher? = null

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        settings = SettingsDataStore.default(app)
        libraryPrefs = LibraryPrefsStore.default(app)
        kotlinx.coroutines.runBlocking {
            settings.clear()
            libraryPrefs.setDiscsSort(LibraryPrefsStore.DiscsSort.RECENT)
            libraryPrefs.setDiscsView(LibraryPrefsStore.DiscsView.LIST)
        }
        library = FakeLibraryRepo()
        playback = FakePlaybackRepo()
    }

    @After
    fun tearDown() {
        // No resetMain — suspended collectors make reset throw.
    }

    private fun TestScope.vm(): LibraryViewModel {
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher!!)
        val vm = LibraryViewModel(
            library, playback, settings, libraryPrefs, downloadManager(),
        )
        advanceUntilIdle()
        return vm
    }

    private fun downloadManager() = DownloadManager(
        context = RuntimeEnvironment.getApplication(),
        resolver = NeverResolve(),
        okHttp = okhttp3.OkHttpClient(),
        repository = FakeDownloadRepository(),
    )

    private class NeverResolve : StreamResolver {
        override suspend fun resolve(
            videoId: String,
            preferredItag: Int,
            force: Boolean,
        ): ResolvedStream = throw UnsupportedOperationException("test")
    }

    private class FakeDownloadRepository : DownloadRepository {
        private val rows = MutableStateFlow<List<DownloadItem>>(emptyList())
        override fun observeAll(): Flow<List<DownloadItem>> = rows
        override fun observe(videoId: String): Flow<DownloadItem?> =
            flow { emit(rows.value.firstOrNull { it.videoId == videoId }) }
        override suspend fun get(videoId: String): DownloadItem? =
            rows.value.firstOrNull { it.videoId == videoId }
        override suspend fun upsert(item: DownloadItem) {
            rows.value = rows.value.filterNot { it.videoId == item.videoId } + item
        }
        override suspend fun delete(videoId: String) {
            rows.value = rows.value.filterNot { it.videoId == videoId }
        }
        override suspend fun clear() { rows.value = emptyList() }
        override suspend fun failActive(error: String) {
            rows.value = rows.value.map {
                if (it.state == DownloadState.QUEUED ||
                    it.state == DownloadState.DOWNLOADING
                ) {
                    it.copy(state = DownloadState.FAILED, error = error)
                } else it
            }
        }
    }

    private fun disc(i: Int) = Disc(
        id = i.toLong(), docId = "d$i", treeUri = "t", folderPath = "f$i",
        title = "Disc $i", performer = "p", composer = "c$i", genre = null,
        date = null, discId = null, discNumber = null, totalDiscs = null,
        cueUri = null, flacUri = "u$i", logUri = null, artworkUri = null,
        albumGainDb = null, albumPeak = null, flacDurationMs = null,
        flacSizeBytes = 0, flacLastModified = null, scannedAt = 0L,
    )

    private fun track(id: Long, discId: Long, no: Int) = Track(
        id = id, discId = discId, trackNo = no, title = "T$no",
        performer = "p", composer = "c", startMs = 0, endMs = 1000,
        pregapStartMs = null, gainDb = null, peak = null, isrc = null, flags = 0,
    )

    @Test
    fun `no library root yields empty state`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.state.value.hasRoot).isFalse()
        assertThat(vm.state.value.discs).isEmpty()
    }

    @Test
    fun `grant persists root and starts scan`() = runTest {
        val vm = vm()
        vm.onTreePicked(
            LibraryViewModel.PickerResult.Granted(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            ),
        )
        advanceUntilIdle()
        assertThat(settings.libraryRootTreeUri.first()).contains("externalstorage")
        assertThat(library.scanCalls).isGreaterThan(0)
    }

    @Test
    fun `scan progress is monotonic`() = runTest {
        val vm = vm()
        vm.rescan()
        advanceUntilIdle()
        // The driver emitted a strictly increasing count then a null-folder
        // completion marker (which the VM maps to scanning=false).
        val counts = library.progressHistory.map { it.discsScanned }
        assertThat(counts).isEqualTo(counts.sorted())
        assertThat(counts.last()).isAtLeast(3)
        assertThat(vm.state.value.scanning).isFalse()
    }

    @Test
    fun `100 disc 1669 track scan reports summary`() = runTest {
        val vm = vm()
        library.nextScan = ScanResult(100, 1669, 28_500, 0)
        vm.rescan()
        advanceUntilIdle()
        val last = vm.state.value.lastScan
        assertThat(last).isNotNull()
        assertThat(last!!.newDiscs).isEqualTo(100)
        assertThat(last.tracks).isEqualTo(1669)
        assertThat(vm.state.value.scanning).isFalse()
    }

    @Test
    fun `shrinking scan requires explicit confirm before clearing (M2)`() = runTest {
        val vm = vm()
        library.discsFlow.value = (1..3).map { disc(it) }
        advanceUntilIdle()
        library.nextScan = ScanResult(1, 10, 10, 2)
        vm.rescan()
        advanceUntilIdle()
        // Shrink detected → pending confirm; nothing cleared yet.
        assertThat(vm.state.value.pendingShrink).isNotNull()
        assertThat(library.clearCalls).isEqualTo(0)
        vm.confirmShrink()
        advanceUntilIdle()
        assertThat(library.clearCalls).isEqualTo(1)
    }

    @Test
    fun `missing favourites produce the tombstone banner count (B1)`() = runTest {
        playback.favs.value = listOf(
            FavoriteItem("m1", "a", null, null, MediaSource.LOCAL, 0, missing = true),
            FavoriteItem("m2", "b", null, null, MediaSource.LOCAL, 0, missing = true),
            FavoriteItem("m3", "c", null, null, MediaSource.LOCAL, 0, missing = true),
            FavoriteItem("m4", "d", null, null, MediaSource.LOCAL, 0, missing = false),
        )
        val vm = vm()
        // The combined state needs the DataStore emit (real IO) — poll.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (vm.state.value.missingFavorites != 3 &&
            System.nanoTime() < deadline) {
            Thread.sleep(25); testScheduler.runCurrent()
        }
        assertThat(vm.state.value.missingFavorites).isEqualTo(3)
    }

    // --- user playlists -----------------------------------------------------

    private fun queueItem(mediaId: String, position: Int = 0) = QueueItem(
        position = position,
        mediaId = mediaId,
        title = "t-$mediaId",
        artist = null,
        artworkUri = null,
        source = MediaSource.LOCAL,
        durationMs = null,
        addedAt = 0,
    )

    /** Polls until [cond] holds — combine also waits on the real DataStore. */
    private fun TestScope.await(cond: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!cond() && System.nanoTime() < deadline) {
            Thread.sleep(25); testScheduler.runCurrent()
        }
    }

    @Test
    fun `playlists flow into ui state`() = runTest {
        playback.playlistsFlow.value = listOf(
            Playlist(1, "Mix", createdAt = 1, trackCount = 2),
        )
        val vm = vm()
        await { vm.state.value.playlists.size == 1 }
        assertThat(vm.state.value.playlists.single().name).isEqualTo("Mix")
    }

    @Test
    fun `selecting a playlist exposes its tracks then backs out`() = runTest {
        playback.playlistsFlow.value = listOf(Playlist(7, "P", 1, 2))
        playback.playlistTracks.value = mapOf(
            7L to listOf(queueItem("pianostream://local/a"), queueItem("pianostream://local/b", 1)),
        )
        val vm = vm()
        vm.selectPlaylist(7)
        await { vm.state.value.playlistTracks.size == 2 }
        assertThat(vm.state.value.selectedPlaylistId).isEqualTo(7)
        assertThat(vm.state.value.playlistTracks.map { it.mediaId })
            .containsExactly("pianostream://local/a", "pianostream://local/b").inOrder()
        vm.selectPlaylist(null)
        advanceUntilIdle()
        assertThat(vm.state.value.playlistTracks).isEmpty()
    }

    @Test
    fun `createPlaylistAndAdd creates then adds the track`() = runTest {
        val vm = vm()
        vm.createPlaylistAndAdd("Road", queueItem("pianostream://local/x"))
        await { vm.state.value.playlists.size == 1 }
        assertThat(vm.state.value.playlists.single().name).isEqualTo("Road")
        vm.selectPlaylist(1)
        await { vm.state.value.playlistTracks.isNotEmpty() }
        assertThat(vm.state.value.playlistTracks.single().mediaId)
            .isEqualTo("pianostream://local/x")
    }

    // --- library v3 additions -------------------------------------------------

    @Test
    fun `library query lives in ui state`() = runTest {
        val vm = vm()
        assertThat(vm.state.value.libraryQuery).isEmpty()
        vm.setLibraryQuery("bach")
        advanceUntilIdle()
        assertThat(vm.state.value.libraryQuery).isEqualTo("bach")
        vm.setLibraryQuery("")
        advanceUntilIdle()
        assertThat(vm.state.value.libraryQuery).isEmpty()
    }

    @Test
    fun `discs sort and view persist through the prefs store`() = runTest {
        val vm = vm()
        assertThat(vm.state.value.discsSort)
            .isEqualTo(LibraryPrefsStore.DiscsSort.RECENT)
        assertThat(vm.state.value.discsView)
            .isEqualTo(LibraryPrefsStore.DiscsView.LIST)

        vm.setDiscsSort(LibraryPrefsStore.DiscsSort.ARTIST)
        vm.toggleDiscsView()
        await {
            vm.state.value.discsSort == LibraryPrefsStore.DiscsSort.ARTIST &&
                vm.state.value.discsView == LibraryPrefsStore.DiscsView.GRID
        }
        assertThat(libraryPrefs.discsSort.first())
            .isEqualTo(LibraryPrefsStore.DiscsSort.ARTIST)
        assertThat(libraryPrefs.discsView.first())
            .isEqualTo(LibraryPrefsStore.DiscsView.GRID)
    }

    @Test
    fun `moveSelectedPlaylistItem reorders the open playlist`() = runTest {
        playback.playlistsFlow.value = listOf(Playlist(7, "P", 1, 3))
        playback.playlistTracks.value = mapOf(
            7L to listOf(
                queueItem("m/a", 0),
                queueItem("m/b", 1),
                queueItem("m/c", 2),
            ),
        )
        val vm = vm()
        vm.selectPlaylist(7)
        await { vm.state.value.playlistTracks.size == 3 }
        vm.moveSelectedPlaylistItem(2, 0)
        await {
            vm.state.value.playlistTracks.map { it.mediaId } ==
                listOf("m/c", "m/a", "m/b")
        }
        assertThat(vm.state.value.playlistTracks.map { it.position })
            .containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `updateSelectedPlaylistDetails writes name and description`() = runTest {
        playback.playlistsFlow.value = listOf(Playlist(7, "P", 1, 0))
        val vm = vm()
        vm.selectPlaylist(7)
        vm.updateSelectedPlaylistDetails("New name", "Night drives")
        await { vm.state.value.playlists.first().name == "New name" }
        assertThat(vm.state.value.playlists.first().description)
            .isEqualTo("Night drives")
    }

    @Test
    fun `playlistsContaining reports membership for the add sheet`() = runTest {
        playback.playlistTracks.value = mapOf(
            1L to listOf(queueItem("m/a", 0)),
            2L to listOf(queueItem("m/b", 0), queueItem("m/a", 1)),
        )
        val vm = vm()
        val containing = vm.playlistsContaining("m/a").first()
        assertThat(containing).containsExactly(1L, 2L)
        val none = vm.playlistsContaining("m/zzz").first()
        assertThat(none).isEmpty()
    }

    @Test
    fun `removeFromAllPlaylists drops the track everywhere`() = runTest {
        playback.playlistTracks.value = mapOf(
            1L to listOf(queueItem("m/a", 0), queueItem("m/b", 1)),
            2L to listOf(queueItem("m/a", 0)),
        )
        val vm = vm()
        vm.removeFromAllPlaylists("m/a")
        await {
            playback.playlistTracks.value.values.flatten()
                .none { it.mediaId == "m/a" }
        }
        assertThat(playback.playlistTracks.value[1L]!!.map { it.mediaId })
            .containsExactly("m/b")
        assertThat(playback.playlistTracks.value[2L]).isEmpty()
    }
}
