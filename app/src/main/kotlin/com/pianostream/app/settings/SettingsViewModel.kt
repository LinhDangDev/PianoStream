package com.pianostream.app.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.BuildConfig
import com.pianostream.core.data.backup.BackupManager
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.settings.SettingsDataStore.ReplayGainMode
import com.pianostream.core.data.settings.SettingsDataStore.TaskRemovalPolicy
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.media.downloads.DownloadManager
import com.pianostream.feature.search.RecentSearchesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Every frozen `SettingsDataStore` key is reachable and persisted through
 * this VM (C1/C5). `updaterEnabled` ≠ `remoteConfigEnabled` — separate
 * consent domains (F2/F15): enabling remote config (or a force refresh)
 * routes through a confirmation that names the pinned registry host.
 */
@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val playback: PlaybackRepository,
    private val wiper: LocalDataWiper,
    private val downloadManager: DownloadManager,
    private val backupManager: BackupManager,
    private val recentSearches: RecentSearchesStore,
) : ViewModel() {

    public data class UiState(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
        val replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
        val preampDb: Double = 0.0,
        val crossfadeMs: Int = 0,
        val taskRemovalPolicy: TaskRemovalPolicy = TaskRemovalPolicy.STOP,
        val preferredItag: Int = 251,
        val cacheBudgetBytes: Long = 1L shl 30,
        val localOnlySearch: Boolean = false,
        val sendSearchSuggestions: Boolean = false,
        val updaterEnabled: Boolean = false,
        val remoteConfigEnabled: Boolean = false,
        val libraryRootTreeUri: String? = null,
        val libraryLastScanAt: Long? = null,
        /** YouTube audio quality tier — "AUTO"/"LOW"/"NORMAL"/"HIGH". */
        val audioItagTier: String = SettingsDataStore.DEFAULT_AUDIO_ITAG_TIER,
        val monoAudio: Boolean = false,
        val gapless: Boolean = true,
        val normalizeVolume: Boolean = false,
        /** Settings-search query — transient, never persisted. */
        val searchQuery: String = "",
        /** Present the pinned-host consent dialog for remote config (F2/F15). */
        val confirmRemoteConfig: Boolean = false,
        /** First delete-all confirm; second tap inside dialog commits (R2). */
        val confirmDeleteAll: Boolean = false,
        val deleteAllRunning: Boolean = false,
        /** Finished downloads on disk — the Storage section's summary row. */
        val downloadCount: Int = 0,
        val downloadBytes: Long = 0L,
        /** "Clear all downloads" confirm dialog. */
        val confirmClearDownloads: Boolean = false,
        /** Backup & restore card state (transient — never persisted). */
        val includeBackupDownloads: Boolean = true,
        val backupRunning: Boolean = false,
        /** Progress ("Exporting…") or the last result line. */
        val backupStatus: String? = null,
    )

    private data class DialogState(
        val remoteConfig: Boolean = false,
        val deleteAll: Boolean = false,
        val wiping: Boolean = false,
        val clearDownloads: Boolean = false,
    )

    private data class BackupState(
        val includeDownloads: Boolean = true,
        val running: Boolean = false,
        val status: String? = null,
    )

    private val dialogs = MutableStateFlow(DialogState())

    private val backup = MutableStateFlow(BackupState())

    /** Settings-search query — in-memory only (a screen concern, not a pref). */
    private val searchQuery = MutableStateFlow("")

    public val state: StateFlow<UiState> = combine(
        combine(
            settings.themeMode,
            settings.dynamicColor,
            settings.replayGainMode,
            settings.preampDb,
            settings.crossfadeMs,
            settings.taskRemovalPolicy,
            settings.preferredItag,
            settings.cacheBudgetBytes,
            settings.localOnlySearch,
            settings.sendSearchSuggestions,
        ) { v: Array<Any?> -> v },
        combine(
            settings.updaterEnabled,
            settings.remoteConfigEnabled,
            settings.libraryRootTreeUri,
            settings.libraryLastScanAt,
            dialogs,
        ) { v: Array<Any?> -> v },
        combine(
            settings.audioItagTier,
            settings.monoAudio,
            settings.gapless,
            settings.normalizeVolume,
            searchQuery,
            downloadManager.downloads,
            backup,
        ) { v: Array<Any?> -> v },
    ) { a: Array<Any?>, b: Array<Any?>, c: Array<Any?> ->
        UiState(
            themeMode = a[0] as ThemeMode,
            dynamicColor = a[1] as Boolean,
            replayGainMode = a[2] as ReplayGainMode,
            preampDb = a[3] as Double,
            crossfadeMs = a[4] as Int,
            taskRemovalPolicy = a[5] as TaskRemovalPolicy,
            preferredItag = a[6] as Int,
            cacheBudgetBytes = a[7] as Long,
            localOnlySearch = a[8] as Boolean,
            sendSearchSuggestions = a[9] as Boolean,
            updaterEnabled = b[0] as Boolean,
            remoteConfigEnabled = b[1] as Boolean,
            libraryRootTreeUri = b[2] as String?,
            libraryLastScanAt = b[3] as Long?,
            confirmRemoteConfig = (b[4] as DialogState).remoteConfig,
            confirmDeleteAll = (b[4] as DialogState).deleteAll,
            deleteAllRunning = (b[4] as DialogState).wiping,
            audioItagTier = c[0] as String,
            monoAudio = c[1] as Boolean,
            gapless = c[2] as Boolean,
            normalizeVolume = c[3] as Boolean,
            searchQuery = c[4] as String,
            downloadCount = (c[5] as Map<String, DownloadItem>).values
                .count { it.state == DownloadState.DONE },
            downloadBytes = (c[5] as Map<String, DownloadItem>).values
                .filter { it.state == DownloadState.DONE }
                .sumOf { it.bytesDownloaded },
            confirmClearDownloads = (b[4] as DialogState).clearDownloads,
            includeBackupDownloads = (c[6] as BackupState).includeDownloads,
            backupRunning = (c[6] as BackupState).running,
            backupStatus = (c[6] as BackupState).status,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    // Appearance
    public fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    public fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    // Playback
    public fun setReplayGainMode(mode: ReplayGainMode) {
        viewModelScope.launch { settings.setReplayGainMode(mode) }
    }

    public fun setPreampDb(db: Double) {
        viewModelScope.launch { settings.setPreampDb(db) }
    }

    public fun setCrossfadeMs(ms: Int) {
        viewModelScope.launch { settings.setCrossfadeMs(ms) }
    }

    public fun setTaskRemovalPolicy(policy: TaskRemovalPolicy) {
        viewModelScope.launch { settings.setTaskRemovalPolicy(policy) }
    }

    public fun setPreferredItag(itag: Int) {
        viewModelScope.launch { settings.setPreferredItag(itag) }
    }

    /** YouTube audio quality tier — "AUTO"/"LOW"/"NORMAL"/"HIGH". */
    public fun setAudioItagTier(tier: String) {
        viewModelScope.launch { settings.setAudioItagTier(tier) }
    }

    public fun setMonoAudio(enabled: Boolean) {
        viewModelScope.launch { settings.setMonoAudio(enabled) }
    }

    public fun setGapless(enabled: Boolean) {
        viewModelScope.launch { settings.setGapless(enabled) }
    }

    public fun setNormalizeVolume(enabled: Boolean) {
        viewModelScope.launch { settings.setNormalizeVolume(enabled) }
    }

    /** Live settings-search query — transient VM state, never persisted. */
    public fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    // Search & network
    public fun setLocalOnlySearch(enabled: Boolean) {
        viewModelScope.launch { settings.setLocalOnlySearch(enabled) }
    }

    public fun setSendSearchSuggestions(enabled: Boolean) {
        viewModelScope.launch { settings.setSendSearchSuggestions(enabled) }
    }

    public fun setUpdaterEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setUpdaterEnabled(enabled) }
    }

    /** F2/F15: enabling (or force-refreshing) remote config needs consent. */
    public fun requestRemoteConfig(enable: Boolean) {
        if (enable) {
            dialogs.value = dialogs.value.copy(remoteConfig = true)
        } else {
            viewModelScope.launch { settings.setRemoteConfigEnabled(false) }
        }
    }

    public fun confirmRemoteConfig(confirmed: Boolean) {
        dialogs.value = dialogs.value.copy(remoteConfig = false)
        if (confirmed) {
            viewModelScope.launch {
                settings.setRemoteConfigEnabled(true)
                settings.setRemoteConfigLastRefreshAt(System.currentTimeMillis())
            }
        }
    }

    // Storage
    public fun setCacheBudgetBytes(bytes: Long) {
        viewModelScope.launch { settings.setCacheBudgetBytes(bytes) }
    }

    public fun clearStreamCache() {
        viewModelScope.launch { playback.clearStreamCache() }
    }

    // Downloads — same request→confirm dance as delete-all.
    public fun requestClearDownloads() {
        dialogs.value = dialogs.value.copy(clearDownloads = true)
    }

    public fun cancelClearDownloads() {
        dialogs.value = dialogs.value.copy(clearDownloads = false)
    }

    public fun confirmClearDownloads() {
        if (!dialogs.value.clearDownloads) return
        dialogs.value = dialogs.value.copy(clearDownloads = false)
        viewModelScope.launch { downloadManager.clearAll() }
    }

    // Backup & restore — SAF document URIs handed in by the screen.
    public fun setIncludeBackupDownloads(include: Boolean) {
        backup.value = backup.value.copy(includeDownloads = include)
    }

    public fun exportBackup(uri: Uri) {
        if (backup.value.running) return
        val includeFiles = backup.value.includeDownloads
        backup.value = backup.value.copy(running = true, status = "Exporting…")
        viewModelScope.launch {
            val result = runCatching {
                backupManager.exportBackup(
                    uri = uri,
                    includeDownloadFiles = includeFiles,
                    appVersion = BuildConfig.VERSION_NAME,
                    recentSearches = recentSearches.recent.value,
                )
            }
            backup.value = backup.value.copy(
                running = false,
                status = result.fold(
                    onSuccess = {
                        "Exported ${it.playlists} playlists · ${it.favorites} " +
                            "favorites · ${it.downloads} downloads · " +
                            formatBytes(it.downloadBytes)
                    },
                    onFailure = {
                        "Export failed: ${it.message ?: it.javaClass.simpleName}"
                    },
                ),
            )
        }
    }

    public fun restoreBackup(uri: Uri) {
        if (backup.value.running) return
        backup.value = backup.value.copy(running = true, status = "Restoring…")
        viewModelScope.launch {
            val result = runCatching { backupManager.restoreBackup(uri) }
            result.onSuccess { report ->
                // Hot-apply: incoming searches merge ahead of existing ones
                // (record() dedupes + front-inserts, capped at MAX_ENTRIES).
                report.recentSearches.asReversed().forEach {
                    recentSearches.record(it)
                }
            }
            backup.value = backup.value.copy(
                running = false,
                status = result.fold(
                    onSuccess = {
                        "Restored ${it.playlists} playlists · ${it.favorites} " +
                            "favorites · ${it.downloads} downloads"
                    },
                    onFailure = {
                        "Restore failed: ${it.message ?: it.javaClass.simpleName}"
                    },
                ),
            )
        }
    }

    // Library
    public fun revokeLibraryAccess(library: com.pianostream.core.data.library.LibraryRepository) {
        viewModelScope.launch { library.revokeLibraryAccess() }
    }

    // Danger zone — double confirm: request opens dialog, confirm commits.
    public fun requestDeleteAll() {
        dialogs.value = dialogs.value.copy(deleteAll = true)
    }

    public fun cancelDeleteAll() {
        dialogs.value = dialogs.value.copy(deleteAll = false)
    }

    public fun confirmDeleteAll() {
        // M2: confirm is a no-op unless the dialog is actually open —
        // a stray confirm must never wipe.
        if (!dialogs.value.deleteAll || dialogs.value.wiping) return
        dialogs.value = dialogs.value.copy(wiping = true)
        viewModelScope.launch {
            runCatching { wiper.wipeAll() }
            dialogs.value = DialogState()
        }
    }

    /** The pinned registry hosts named in the remote-config consent (F2). */
    public val registryHosts: String get() = BuildConfig.REGISTRY_HOSTS

    private companion object {
        /** Byte count → display string (same format as the Settings screen). */
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }
    }
}
