package com.pianostream.app.settings

import com.google.common.truth.Truth.assertThat
import androidx.test.core.app.ApplicationProvider
import com.pianostream.core.data.backup.BackupManager
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.PlaybackModule
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadRepository
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.data.settings.SettingsDataStore.ReplayGainMode
import com.pianostream.core.data.settings.SettingsDataStore.TaskRemovalPolicy
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.data.settings.SettingsBackupPort
import com.pianostream.core.media.downloads.DownloadManager
import com.pianostream.feature.search.RecentSearchesStore
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import com.pianostream.core.youtube.di.YouTubeLocalWipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
class SettingsViewModelTest {

    private class FakePlaybackRepo : PlaybackRepository {
        var streamCacheClears = 0
        var clearAllCalls = 0
        override fun observeQueue(): Flow<List<QueueItem>> = flowOf(emptyList())
        override fun observeQueueState(): Flow<QueueState?> = flowOf(null)
        override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {}
        override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {}
        override suspend fun saveQueueCursor(state: QueueState) {}
        override fun observeHistory(): Flow<List<PlayHistoryItem>> = flowOf(emptyList())
        override suspend fun appendHistory(item: PlayHistoryItem) {}
        override fun observeFavorites(): Flow<List<FavoriteItem>> = flowOf(emptyList())
        override suspend fun addFavorite(item: FavoriteItem) {}
        override suspend fun removeFavorite(mediaId: String) {}
        override suspend fun isFavorite(mediaId: String): Boolean = false
        override suspend fun reconcileMissing(existingMediaIds: Set<String>) {}
        override suspend fun clearStreamCache() { streamCacheClears++ }
        override suspend fun clearAll() { clearAllCalls++ }
    }

    private class FakeLibraryRepo : LibraryRepository {
        var revokeCalls = 0
        var clearAllCalls = 0
        override val discs: Flow<List<Disc>> = flowOf(emptyList())
        override fun observeTracks(discId: Long): Flow<List<Track>> = flowOf(emptyList())
        override suspend fun discById(id: Long): Disc? = null
        override suspend fun tracksOf(discId: Long): List<Track> = emptyList()
        override fun search(query: String): Flow<List<SearchHit>> = flowOf(emptyList())
        override suspend fun distinctComposers(): List<String> = emptyList()
        override val scanProgress: Flow<ScanProgress> = flowOf()
        override suspend fun startScan(): ScanResult = ScanResult(0, 0, 0, 0)
        override suspend fun revokeLibraryAccess() { revokeCalls++ }
        override suspend fun clearAll() { clearAllCalls++ }
    }

    private class CountingWipe : YouTubeLocalWipe {
        var calls = 0
        override fun wipe() { calls++ }
    }

    private lateinit var settings: SettingsDataStore
    private lateinit var playback: FakePlaybackRepo
    private lateinit var library: FakeLibraryRepo
    private lateinit var ytWipe: CountingWipe
    private lateinit var wiper: LocalDataWiper

    @Before
    fun setUp() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        settings = SettingsDataStore.default(ctx)
        settings.clear()
        playback = FakePlaybackRepo()
        library = FakeLibraryRepo()
        ytWipe = CountingWipe()
        wiper = LocalDataWiper(ctx, playback, library, settings, ytWipe)
    }

    private fun vm() = SettingsViewModel(
        settings,
        playback,
        wiper,
        downloadManager(),
        backupManager(),
        RecentSearchesStore(ApplicationProvider.getApplicationContext()),
    )

    // androidx.room is implementation-scoped in core:data (compile-hidden
    // here), so build the db through the module's public provider instead
    // of Room.inMemoryDatabaseBuilder.
    private fun backupManager() = BackupManager(
        context = ApplicationProvider.getApplicationContext(),
        db = PlaybackModule.playbackDatabase(ApplicationProvider.getApplicationContext()),
        settingsBackup = SettingsBackupPort(settings),
    )

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

    /**
     * Poll until [cond]. VM work posts to the main looper (paused under
     * Robolectric) AND writes DataStore on real IO — pump the looper,
     * then sleep, inside every poll.
     */
    private suspend fun await(ms: Long = 5_000, cond: suspend () -> Boolean) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!cond() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `every frozen key persists through the ViewModel`() = runTest {
        val vm = vm()
        vm.setThemeMode(ThemeMode.DARK)
        vm.setDynamicColor(false)
        vm.setReplayGainMode(ReplayGainMode.ALBUM)
        vm.setPreampDb(1.5)
        vm.setCrossfadeMs(750)
        vm.setTaskRemovalPolicy(TaskRemovalPolicy.PAUSE)
        vm.setPreferredItag(140)
        vm.setLocalOnlySearch(true)
        vm.setSendSearchSuggestions(true)
        vm.setCacheBudgetBytes(2L shl 30)
        vm.setUpdaterEnabled(true)
        vm.setAudioItagTier("HIGH")
        vm.setMonoAudio(true)
        vm.setGapless(false)
        vm.setNormalizeVolume(true)
        await {
            settings.preferredItag.first() == 140 &&
                settings.updaterEnabled.first() &&
                settings.cacheBudgetBytes.first() == (2L shl 30) &&
                settings.audioItagTier.first() == "HIGH" &&
                settings.monoAudio.first() &&
                !settings.gapless.first() &&
                settings.normalizeVolume.first()
        }
        assertThat(settings.themeMode.first()).isEqualTo(ThemeMode.DARK)
        assertThat(settings.dynamicColor.first()).isFalse()
        assertThat(settings.replayGainMode.first()).isEqualTo(ReplayGainMode.ALBUM)
        assertThat(settings.preampDb.first()).isWithin(0.001).of(1.5)
        assertThat(settings.crossfadeMs.first()).isEqualTo(750)
        assertThat(settings.taskRemovalPolicy.first()).isEqualTo(TaskRemovalPolicy.PAUSE)
        assertThat(settings.localOnlySearch.first()).isTrue()
        assertThat(settings.sendSearchSuggestions.first()).isTrue()
        assertThat(settings.audioItagTier.first()).isEqualTo("HIGH")
        assertThat(settings.monoAudio.first()).isTrue()
        assertThat(settings.gapless.first()).isFalse()
        assertThat(settings.normalizeVolume.first()).isTrue()
    }

    @Test
    fun `settings search query is transient vm state`() = runTest {
        val vm = vm()
        assertThat(vm.state.value.searchQuery).isEmpty()
        vm.setSearchQuery("mono")
        await { vm.state.value.searchQuery == "mono" }
        assertThat(vm.state.value.searchQuery).isEqualTo("mono")
        // Transient only — the store holds no searchQuery flow to assert on.
    }

    @Test
    fun `playback toggles default to mono off, gapless on, normalize off`() = runTest {
        val vm = vm()
        await { vm.state.value.gapless }
        assertThat(vm.state.value.audioItagTier).isEqualTo("AUTO")
        assertThat(vm.state.value.monoAudio).isFalse()
        assertThat(vm.state.value.gapless).isTrue()
        assertThat(vm.state.value.normalizeVolume).isFalse()
    }

    @Test
    fun `remote config enable requires the confirmation dialog`() = runTest {
        val vm = vm()
        vm.requestRemoteConfig(true)
        // Dialog shown; setting NOT yet written.
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!vm.state.value.confirmRemoteConfig && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertThat(vm.state.value.confirmRemoteConfig).isTrue()
        assertThat(settings.remoteConfigEnabled.first()).isFalse()
        vm.confirmRemoteConfig(true)
        await {
            settings.remoteConfigEnabled.first() &&
                settings.remoteConfigLastRefreshAt.first() != null
        }
        assertThat(settings.remoteConfigEnabled.first()).isTrue()
        assertThat(settings.remoteConfigLastRefreshAt.first()).isNotNull()
        assertThat(vm.registryHosts).isNotEmpty()
    }

    @Test
    fun `remote config disable is immediate without dialog`() = runTest {
        val vm = vm()
        vm.requestRemoteConfig(false)
        assertThat(vm.state.value.confirmRemoteConfig).isFalse()
        await { true }
        assertThat(settings.remoteConfigEnabled.first()).isFalse()
    }

    @Test
    fun `clearStreamCache reaches the playback repository`() = runTest {
        val vm = vm()
        vm.clearStreamCache()
        await { playback.streamCacheClears > 0 }
        assertThat(playback.streamCacheClears).isEqualTo(1)
    }

    @Test
    fun `revoke library access reaches the library repository`() = runTest {
        val vm = vm()
        vm.revokeLibraryAccess(library)
        await { library.revokeCalls > 0 }
        assertThat(library.revokeCalls).isEqualTo(1)
    }

    @Test
    fun `DELETE ALL needs request then confirm and wipes everything`() = runTest {
        settings.setThemeMode(ThemeMode.LIGHT)
        val vm = vm()
        // No wipe without the dialog.
        vm.confirmDeleteAll()
        await { playback.clearAllCalls > 0 }
        assertThat(playback.clearAllCalls).isEqualTo(0)

        vm.requestDeleteAll()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!vm.state.value.confirmDeleteAll && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertThat(vm.state.value.confirmDeleteAll).isTrue()
        vm.confirmDeleteAll()
        await { playback.clearAllCalls > 0 && library.clearAllCalls > 0 && ytWipe.calls > 0 }
        assertThat(playback.clearAllCalls).isEqualTo(1)
        assertThat(library.clearAllCalls).isEqualTo(1)
        assertThat(ytWipe.calls).isEqualTo(1)
        // DataStore cleared (theme fell back to default).
        assertThat(settings.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test
    fun `cancel delete-all never touches repositories`() = runTest {
        val vm = vm()
        vm.requestDeleteAll()
        vm.cancelDeleteAll()
        ShadowLooper.idleMainLooper()
        assertThat(vm.state.value.confirmDeleteAll).isFalse()
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()
        assertThat(playback.clearAllCalls).isEqualTo(0)
        assertThat(library.clearAllCalls).isEqualTo(0)
    }
}
