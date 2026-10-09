package com.pianostream.feature.search

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadRepository
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.media.downloads.DownloadManager
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import com.pianostream.core.youtube.api.YouTubePage
import com.pianostream.core.youtube.api.YouTubeSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

    private lateinit var mainDispatcher: kotlinx.coroutines.test.TestDispatcher
    private val playback = FakePlaybackRepository()

    private class FakePlaybackRepository : PlaybackRepository {
        override fun observeQueue() = flowOf(emptyList<QueueItem>())
        override fun observeQueueState() = flowOf<QueueState?>(null)
        override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {}
        override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {}
        override suspend fun saveQueueCursor(state: QueueState) {}
        override fun observeHistory() = flowOf(emptyList<PlayHistoryItem>())
        override suspend fun appendHistory(item: PlayHistoryItem) {}
        override fun observeFavorites() = flowOf(emptyList<FavoriteItem>())
        override suspend fun addFavorite(item: FavoriteItem) {}
        override suspend fun removeFavorite(mediaId: String) {}
        override suspend fun isFavorite(mediaId: String) = false
        override suspend fun reconcileMissing(existingMediaIds: Set<String>) {}
        override suspend fun clearStreamCache() {}
        override suspend fun clearAll() {}
    }

    private class CountingYouTubeClient : YouTubeClient {
        var searchCalls = 0
        var browseCalls = 0
        var suggestionCalls = 0
        val queries = mutableListOf<String>()

        override suspend fun search(
            query: String,
            filter: SearchFilter,
            continuation: String?,
        ): YouTubePage {
            searchCalls++
            queries.add(query)
            return YouTubePage(
                title = null,
                sections = listOf(
                    YouTubeSection(
                        "Songs",
                        listOf(
                            YouTubeItem(
                                YouTubeItem.Kind.TRACK, "aaaaaaaaaaa", null,
                                "Result $query", "ch", null, 1000L,
                            ),
                        ),
                    ),
                ),
                continuation = null,
            )
        }

        override suspend fun browse(browseId: String, continuation: String?): YouTubePage {
            browseCalls++
            return YouTubePage(null, emptyList(), null)
        }

        override suspend fun suggestions(query: String): List<String> {
            suggestionCalls++
            return listOf("$query x")
        }
    }

    private class StubLibraryRepository : LibraryRepository {
        var searchQueries = mutableListOf<String>()
        val hits = listOf(
            SearchHit(trackId = 1, discId = 1, title = "Local hit", snippet = "x"),
        )

        override val discs: Flow<List<Disc>> = flowOf(emptyList())
        override fun observeTracks(discId: Long): Flow<List<Track>> = flowOf(emptyList())
        override suspend fun discById(id: Long): Disc? = null
        override suspend fun tracksOf(discId: Long): List<Track> = emptyList()
        override fun search(query: String): Flow<List<SearchHit>> {
            searchQueries.add(query)
            return flowOf(hits)
        }
        override suspend fun distinctComposers(): List<String> = emptyList()
        override val scanProgress: Flow<ScanProgress> = flowOf(ScanProgress(0, null))
        override suspend fun startScan(): ScanResult = ScanResult(0, 0, 0, 0)
        override suspend fun revokeLibraryAccess() {}
        override suspend fun clearAll() {}
    }

    private lateinit var settings: SettingsDataStore
    private lateinit var recents: RecentSearchesStore
    private lateinit var library: StubLibraryRepository
    private lateinit var youtube: CountingYouTubeClient
    private lateinit var vm: SearchViewModel

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

    @Before
    fun setUp() {
        settings = SettingsDataStore.default(RuntimeEnvironment.getApplication())
        recents = RecentSearchesStore(RuntimeEnvironment.getApplication())
        kotlinx.coroutines.runBlocking {
            settings.clear()
            recents.clear()
        }
        library = StubLibraryRepository()
        youtube = CountingYouTubeClient()
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val m = androidx.lifecycle.ViewModel::class.java
                    .getDeclaredMethod("clear")
                m.isAccessible = true
                m.invoke(vm)
            }
        }
        // No resetMain: prior VMs hold suspended collectors on Main and
        // resetMain throws "used concurrently"; the next test simply
        // replaces Main via setMain.
    }

    /**
     * DataStore `first()` resumes on real IO — pump the test scheduler then
     * poll until the condition lands (bounded).
     */
    private suspend fun kotlinx.coroutines.test.TestScope.eventually(
        timeoutMs: Long = 5_000,
        cond: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            testScheduler.runCurrent()
            advanceUntilIdle()
            Thread.sleep(20)
            testScheduler.runCurrent()
            if (System.nanoTime() > deadline) break
        }
    }

    /** Per-test main dispatcher sharing the runTest scheduler (debounce!). */
    private suspend fun makeVm(
        scope: kotlinx.coroutines.test.TestScope,
    ): SearchViewModel {
        mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val vm = SearchViewModel(
            library, youtube, settings, playback, recents, downloadManager(),
        )
        scope.advanceUntilIdle()
        return vm
    }

    @Test
    fun `rapid query changes run once after 300ms debounce`() = runTest() {
        vm = makeVm(this)
        vm.onQueryChanged("h")
        vm.onQueryChanged("ha")
        vm.onQueryChanged("haydn")
        advanceTimeBy(299)
        assertThat(library.searchQueries).isEmpty()
        advanceTimeBy(2)
        advanceUntilIdle()
        eventually { library.searchQueries.isNotEmpty() }
        // Exactly one run of the final query (verbatim, F16).
        assertThat(library.searchQueries).containsExactly("haydn")
    }

    @Test
    fun `raw input forwarded verbatim to library search`() = runTest() {
        vm = makeVm(this)
        vm.onQueryChanged("""OR NEAR( "quoted" * """)
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { library.searchQueries.isNotEmpty() }
        assertThat(library.searchQueries).containsExactly("""OR NEAR( "quoted" * """)
    }

    @Test
    fun `localOnlySearch zero YouTube calls (F12)`() = runTest() {
        settings.setLocalOnlySearch(true)
        vm = makeVm(this)
        vm.onQueryChanged("mozart")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { library.searchQueries.isNotEmpty() }
        Thread.sleep(100)
        assertThat(youtube.searchCalls).isEqualTo(0)
        assertThat(youtube.browseCalls).isEqualTo(0)
        assertThat(youtube.suggestionCalls).isEqualTo(0)
        assertThat(vm.state.value.localHits).isNotEmpty()
    }

    @Test
    fun `LOCAL scope never calls YouTube (F12)`() = runTest() {
        vm = makeVm(this)
        vm.acceptYoutubeDisclosure()
        vm.setScope(SearchViewModel.Scope.LOCAL)
        vm.onQueryChanged("mozart")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { library.searchQueries.isNotEmpty() }
        Thread.sleep(100)
        assertThat(youtube.searchCalls).isEqualTo(0)
        assertThat(youtube.suggestionCalls).isEqualTo(0)
    }

    @Test
    fun `suggestions only when enabled and youtube scope (F12)`() = runTest() {
        settings.setSendSearchSuggestions(false)
        vm = makeVm(this)
        vm.acceptYoutubeDisclosure()
        vm.setScope(SearchViewModel.Scope.YOUTUBE)
        vm.onQueryChanged("chop")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { youtube.searchCalls == 1 }
        assertThat(youtube.suggestionCalls).isEqualTo(0)
        assertThat(youtube.searchCalls).isEqualTo(1)

        settings.setSendSearchSuggestions(true)
        vm.onQueryChanged("chopin")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { youtube.suggestionCalls > 0 }
        assertThat(youtube.suggestionCalls).isGreaterThan(0)
    }

    @Test
    fun `submit records query in recents, re-run moves to front`() = runTest() {
        vm = makeVm(this)
        vm.onQueryChanged("haydn")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { recents.recent.value.isNotEmpty() }
        assertThat(recents.recent.value).containsExactly("haydn")

        vm.onQueryChanged("mozart")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { recents.recent.value.firstOrNull() == "mozart" }
        assertThat(recents.recent.value).containsExactly("mozart", "haydn").inOrder()

        // Re-running an existing entry dedupes it back to the front.
        vm.onQueryChanged("haydn")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { recents.recent.value.firstOrNull() == "haydn" }
        assertThat(recents.recent.value).containsExactly("haydn", "mozart").inOrder()
    }

    @Test
    fun `recents cap at ten entries`() = runTest() {
        vm = makeVm(this)
        repeat(12) { i -> recents.record("q$i") }
        advanceUntilIdle()
        eventually { vm.state.value.recentQueries.size == 10 }
        assertThat(vm.state.value.recentQueries).hasSize(10)
        assertThat(vm.state.value.recentQueries.first()).isEqualTo("q11")
        assertThat(vm.state.value.recentQueries).doesNotContain("q0")
    }

    @Test
    fun `removeRecent drops one entry, clearRecents wipes all`() = runTest() {
        vm = makeVm(this)
        recents.record("a")
        recents.record("b")
        advanceUntilIdle()
        eventually { vm.state.value.recentQueries.size == 2 }
        vm.removeRecent("a")
        advanceUntilIdle()
        eventually { vm.state.value.recentQueries == listOf("b") }
        assertThat(vm.state.value.recentQueries).containsExactly("b")
        vm.clearRecents()
        advanceUntilIdle()
        eventually { vm.state.value.recentQueries.isEmpty() }
        assertThat(vm.state.value.recentQueries).isEmpty()
    }

    @Test
    fun `pasted link records the resolved title`() = runTest() {
        vm = makeVm(this)
        vm.onQueryChanged("https://youtu.be/aaaaaaaaaaa")
        advanceTimeBy(400)
        advanceUntilIdle()
        eventually { youtube.searchCalls > 0 }
        eventually { recents.recent.value.isNotEmpty() }
        assertThat(recents.recent.value.first())
            .isEqualTo("Result https://youtu.be/aaaaaaaaaaa")
        assertThat(recents.recent.value).hasSize(1)
    }

    @Test
    fun `type filter defaults to ALL and switches`() = runTest() {
        vm = makeVm(this)
        assertThat(vm.state.value.typeFilter).isEqualTo(SearchViewModel.TypeFilter.ALL)
        vm.setTypeFilter(SearchViewModel.TypeFilter.VIDEOS)
        assertThat(vm.state.value.typeFilter).isEqualTo(SearchViewModel.TypeFilter.VIDEOS)
    }

    @Test
    fun `youtube skipped until disclosure accepted`() = runTest() {
        vm = makeVm(this)
        vm.setScope(SearchViewModel.Scope.YOUTUBE)
        vm.onQueryChanged("haydn")
        advanceTimeBy(400)
        advanceUntilIdle()
        Thread.sleep(100)
        assertThat(youtube.searchCalls).isEqualTo(0)
        vm.acceptYoutubeDisclosure()
        advanceUntilIdle()
        eventually { youtube.searchCalls > 0 }
        assertThat(youtube.searchCalls).isGreaterThan(0)
    }
}
