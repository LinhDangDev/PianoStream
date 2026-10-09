package com.pianostream.feature.home

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
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import com.pianostream.core.youtube.api.YouTubePage
import com.pianostream.core.youtube.api.YouTubeSection
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    private class FakeLibraryRepo : LibraryRepository {
        val discsFlow = MutableStateFlow<List<Disc>>(emptyList())
        var composerList = listOf("Bach", "Chopin")
        override val discs: Flow<List<Disc>> = discsFlow
        override fun observeTracks(discId: Long): Flow<List<Track>> = flowOf(emptyList())
        override suspend fun discById(id: Long): Disc? = null
        override suspend fun tracksOf(discId: Long): List<Track> = emptyList()
        override fun search(query: String): Flow<List<SearchHit>> = flowOf(emptyList())
        override suspend fun distinctComposers(): List<String> = composerList
        override val scanProgress: Flow<ScanProgress> = flowOf()
        override suspend fun startScan(): ScanResult = ScanResult(0, 0, 0, 0)
        override suspend fun revokeLibraryAccess() {}
        override suspend fun clearAll() {}
    }

    private class FakePlaybackRepo : PlaybackRepository {
        val history = MutableStateFlow<List<PlayHistoryItem>>(emptyList())
        override fun observeQueue(): Flow<List<QueueItem>> = flowOf(emptyList())
        override fun observeQueueState(): Flow<QueueState?> = flowOf(null)
        override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {}
        override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {}
        override suspend fun saveQueueCursor(state: QueueState) {}
        override fun observeHistory(): Flow<List<PlayHistoryItem>> = history
        override suspend fun appendHistory(item: PlayHistoryItem) {}
        override fun observeFavorites(): Flow<List<FavoriteItem>> = flowOf(emptyList())
        override suspend fun addFavorite(item: FavoriteItem) {}
        override suspend fun removeFavorite(mediaId: String) {}
        override suspend fun isFavorite(mediaId: String): Boolean = false
        override suspend fun reconcileMissing(existingMediaIds: Set<String>) {}
        override suspend fun clearStreamCache() {}
        override suspend fun clearAll() {}
    }

    private class CountingYouTube : YouTubeClient {
        var browseCalls = 0
        var searchCalls = 0
        var suggestionCalls = 0
        override suspend fun search(
            query: String,
            filter: SearchFilter,
            continuation: String?,
        ): YouTubePage {
            searchCalls++
            return YouTubePage("s", emptyList(), null)
        }
        override suspend fun browse(browseId: String, continuation: String?): YouTubePage {
            browseCalls++
            return YouTubePage(
                "home",
                listOf(
                    YouTubeSection(
                        "Listen again",
                        listOf(
                            YouTubeItem(
                                YouTubeItem.Kind.TRACK, "aaaaaaaaaaa", null,
                                "Nocturne", "Chopin", null, 300_000,
                            ),
                        ),
                    ),
                ),
                null,
            )
        }
        override suspend fun suggestions(query: String): List<String> {
            suggestionCalls++
            return emptyList()
        }
    }

    private lateinit var settings: SettingsDataStore
    private lateinit var library: FakeLibraryRepo
    private lateinit var playback: FakePlaybackRepo
    private lateinit var youtube: CountingYouTube

    @Before
    fun setUp() = runBlocking {
        settings = SettingsDataStore.default(RuntimeEnvironment.getApplication())
        settings.clear()
        library = FakeLibraryRepo()
        playback = FakePlaybackRepo()
        youtube = CountingYouTube()
    }

    /**
     * Subscribe until [cond]. UiState is WhileSubscribed — plain `.value`
     * never updates without a collector, so this must actually collect.
     * Posts land on the paused main looper — pump it while waiting.
     */
    private suspend fun awaitState(
        vm: HomeViewModel,
        ms: Long = 5_000,
        cond: (HomeViewModel.UiState) -> Boolean,
    ) {
        // Collector on a real thread; the VM's WhileSubscribed upstream
        // posts to the paused main looper — pump it until the condition
        // lands or the deadline passes.
        val seen = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            seen.set(vm.state.firstOrNull(cond)?.let { true } ?: false)
        }
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!seen.get() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper()
        job.cancel()
    }

    @Test
    fun `home surfaces history discs composers and youtube shelves`() = runTest {
        playback.history.value = (1..12).map {
            PlayHistoryItem(mediaId = "m$it", title = "T$it", artist = null, artworkUri = null, source = MediaSource.LOCAL, playedAt = it * 1000L, playedMs = 60_000L)
        } + PlayHistoryItem(
            // A repeat play of an older mediaId — must not produce a
            // second card in the rail.
            mediaId = "m3", title = "T3 again", artist = null, artworkUri = null,
            source = MediaSource.LOCAL, playedAt = 500L, playedMs = 60_000L,
        )
        val vm = HomeViewModel(playback, library, youtube, settings)
        // Composers+history arrive via DataStore-backed combine; YouTube
        // via the VM's own init launch.
        awaitState(vm) { it.youtubeSections.isNotEmpty() && it.composers.isNotEmpty() }
        // recentItems: deduped by mediaId, capped at 10.
        assertThat(vm.state.value.recentItems).hasSize(10)
        assertThat(vm.state.value.recentItems.map { it.mediaId }).containsNoDuplicates()
        assertThat(vm.state.value.composers).containsExactly("Bach", "Chopin")
        assertThat(vm.state.value.youtubeSections.first().title)
            .isEqualTo("Listen again")
        assertThat(vm.state.value.youtubeEnabled).isTrue()
        assertThat(youtube.browseCalls).isEqualTo(1)
    }

    @Test
    fun `local-only mode makes zero YouTube calls (F12)`() = runTest {
        settings.setLocalOnlySearch(true)
        val vm = HomeViewModel(playback, library, youtube, settings)
        // Give the VM's init launches a chance (they'd browse if buggy).
        awaitState(vm, 400) { it.youtubeSections.isNotEmpty() }
        ShadowLooper.idleMainLooper()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper()
        assertThat(youtube.browseCalls).isEqualTo(0)
        assertThat(youtube.searchCalls).isEqualTo(0)
        assertThat(youtube.suggestionCalls).isEqualTo(0)
        assertThat(vm.state.value.youtubeEnabled).isFalse()
        assertThat(vm.state.value.youtubeSections).isEmpty()
    }
}
