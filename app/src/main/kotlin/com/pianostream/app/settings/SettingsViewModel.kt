package com.pianostream.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pianostream.BuildConfig
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.settings.SettingsDataStore.ReplayGainMode
import com.pianostream.core.data.settings.SettingsDataStore.TaskRemovalPolicy
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.data.playback.PlaybackRepository
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
        /** Present the pinned-host consent dialog for remote config (F2/F15). */
        val confirmRemoteConfig: Boolean = false,
        /** First delete-all confirm; second tap inside dialog commits (R2). */
        val confirmDeleteAll: Boolean = false,
        val deleteAllRunning: Boolean = false,
    )

    private data class DialogState(
        val remoteConfig: Boolean = false,
        val deleteAll: Boolean = false,
        val wiping: Boolean = false,
    )

    private val dialogs = MutableStateFlow(DialogState())

    public val state: StateFlow<UiState> = combine(
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
    ) { v: Array<Any?> ->
        v
    }.combine(
        combine(
            settings.updaterEnabled,
            settings.remoteConfigEnabled,
            settings.libraryRootTreeUri,
            settings.libraryLastScanAt,
            dialogs,
        ) { v: Array<Any?> -> v },
    ) { a: Array<Any?>, b: Array<Any?> ->
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
}
