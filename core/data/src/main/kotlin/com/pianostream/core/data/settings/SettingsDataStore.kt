package com.pianostream.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Frozen settings surface (Interface Freeze — CC5, SEC-F12/F15/F18).
 *
 * Two separate consent domains, never interchangeable:
 *  - `updaterEnabled` (default false) gates the updater path only —
 *    UpdateChecker/download/install prompt; `github` dist only.
 *  - `remoteConfigEnabled` (default false) gates the remote player-config
 *    registry fetch only, via `RemotePlayerConfigStore`; when false the store
 *    emits **zero** network requests.
 *
 * Privacy defaults: `localOnlySearch` defaults false because YouTube is a core
 * feature (disclosed at first run); when true zero `YouTubeClient` calls are
 * made (leak-tested). `sendSearchSuggestions` defaults false; no
 * `get_search_suggestions` while a local-scope filter is active (F12).
 *
 * Actions are repository methods / orchestrating actions, **not** keys:
 * `PlaybackRepository.clearStreamCache()` (+ a Settings row showing
 * `SimpleCache.getCacheSpace()`), `LibraryRepository.revokeLibraryAccess()`
 * (`releasePersistableUriPermission` + clears `libraryRootTreeUri`), and the
 * one canonical wipe action name `deleteAllLocalData()` (CC10 — spans every
 * data store, so it freezes no single-repository receiver):
 *
 *  - releases the persisted SAF URI permission and clears `libraryRootTreeUri`
 *  - wipes `library.db` + `playback.db` including `-wal`/`-shm`
 *  - clears this DataStore
 *  - clears `cacheDir/media3`, the Coil disk cache, `filesDir/player_configs.json`
 *  - zeroes the poToken GVS/visitor caches and `visitorData`, which are
 *    in-memory singletons inside `:core:youtube` — never persisted, never in
 *    DataStore (SEC-F12); there is no `filesDir/potoken/`
 *
 * `play_history` is capped at 500 rows / 90 days; `cue_sheets_raw` stores only
 * a content hash + parserVersion (re-parse from SAF on demand — classified as
 * a rebuildable cache).
 */
public class SettingsDataStore internal constructor(
    private val store: androidx.datastore.core.DataStore<Preferences>,
) {

    public enum class ReplayGainMode { OFF, TRACK, ALBUM }

    public enum class TaskRemovalPolicy { STOP, PAUSE }

    public enum class ThemeMode { SYSTEM, LIGHT, DARK }

    public companion object {
        public const val STORE_NAME: String = "pianostream.settings"

        public const val DEFAULT_REPLAY_GAIN_MODE: String = "TRACK"
        public const val DEFAULT_PREAMP_DB: Double = 0.0
        public const val DEFAULT_CACHE_BUDGET_BYTES: Long = 512L * 1024L * 1024L
        public const val MIN_CACHE_BUDGET_BYTES: Long = 256L * 1024L * 1024L
        public const val MAX_CACHE_BUDGET_BYTES: Long = 4L * 1024L * 1024L * 1024L
        public const val DEFAULT_PREFERRED_ITAG: Int = 251
        public const val DEFAULT_CROSSFADE_MS: Int = 2000
        public const val DEFAULT_TASK_REMOVAL_POLICY: String = "STOP"
        public const val DEFAULT_THEME_MODE: String = "SYSTEM"
        public const val DEFAULT_DYNAMIC_COLOR: Boolean = true
        public const val DEFAULT_UPDATER_ENABLED: Boolean = false
        public const val DEFAULT_REMOTE_CONFIG_ENABLED: Boolean = false
        public const val DEFAULT_LOCAL_ONLY_SEARCH: Boolean = false
        public const val DEFAULT_SEND_SEARCH_SUGGESTIONS: Boolean = false

        /**
         * Streaming/playback keys `:core:media` reads BY NAME — the exact
         * preference key strings are an integration contract; do not rename.
         */
        public const val DEFAULT_AUDIO_ITAG_TIER: String = "AUTO"
        public const val DEFAULT_MONO_AUDIO: Boolean = false
        public const val DEFAULT_GAPLESS: Boolean = true
        public const val DEFAULT_NORMALIZE_VOLUME: Boolean = false

        private val KEY_REPLAY_GAIN_MODE = stringPreferencesKey("replayGainMode")
        private val KEY_PREAMP_DB = doublePreferencesKey("preampDb")
        private val KEY_CACHE_BUDGET_BYTES = longPreferencesKey("cacheBudgetBytes")
        private val KEY_PREFERRED_ITAG = intPreferencesKey("preferredItag")
        private val KEY_VIDEO_ITAG = intPreferencesKey("videoItag")
        private val KEY_CROSSFADE_MS = intPreferencesKey("crossfadeMs")
        private val KEY_TASK_REMOVAL_POLICY = stringPreferencesKey("taskRemovalPolicy")
        private val KEY_LIBRARY_ROOT_TREE_URI = stringPreferencesKey("libraryRootTreeUri")
        private val KEY_LIBRARY_LAST_SCAN_AT = longPreferencesKey("libraryLastScanAt")
        private val KEY_THEME_MODE = stringPreferencesKey("themeMode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamicColor")
        private val KEY_UPDATER_ENABLED = booleanPreferencesKey("updaterEnabled")
        private val KEY_REMOTE_CONFIG_ENABLED = booleanPreferencesKey("remoteConfigEnabled")
        private val KEY_REMOTE_CONFIG_LAST_REFRESH_AT =
            longPreferencesKey("remoteConfigLastRefreshAt")
        private val KEY_LOCAL_ONLY_SEARCH = booleanPreferencesKey("localOnlySearch")
        private val KEY_SEND_SEARCH_SUGGESTIONS =
            booleanPreferencesKey("sendSearchSuggestions")
        private val KEY_AUDIO_ITAG_TIER = stringPreferencesKey("audioItagTier")
        private val KEY_MONO_AUDIO = booleanPreferencesKey("monoAudio")
        private val KEY_GAPLESS = booleanPreferencesKey("gapless")
        private val KEY_NORMALIZE_VOLUME = booleanPreferencesKey("normalizeVolume")

        private val Context.settingsStore by preferencesDataStore(STORE_NAME)

        /** The application-wide store. */
        public fun default(context: Context): SettingsDataStore =
            SettingsDataStore(context.settingsStore)
    }

    /** The store's data flow, for callers needing raw preferences. */
    public val data: Flow<Preferences> get() = store.data

    // --- playback ------------------------------------------------------------

    /** `replayGainMode(OFF|TRACK|ALBUM)`, default TRACK. */
    public val replayGainMode: Flow<ReplayGainMode> =
        store.data.map { prefs ->
            prefs[KEY_REPLAY_GAIN_MODE]
                ?.let { runCatching { ReplayGainMode.valueOf(it) }.getOrNull() }
                ?: ReplayGainMode.valueOf(DEFAULT_REPLAY_GAIN_MODE)
        }

    public suspend fun setReplayGainMode(mode: ReplayGainMode) {
        store.edit { it[KEY_REPLAY_GAIN_MODE] = mode.name }
    }

    /** `preampDb`, default `0.0`. */
    public val preampDb: Flow<Double> =
        store.data.map { it[KEY_PREAMP_DB] ?: DEFAULT_PREAMP_DB }

    public suspend fun setPreampDb(value: Double) {
        store.edit { it[KEY_PREAMP_DB] = value }
    }

    /**
     * `cacheBudgetBytes`, default 512 MiB, clamped to 256 MiB..4 GiB (E1/fixlist-2
     * R8 — the canonical default is 512 MiB everywhere).
     */
    public val cacheBudgetBytes: Flow<Long> =
        store.data.map { it[KEY_CACHE_BUDGET_BYTES] ?: DEFAULT_CACHE_BUDGET_BYTES }

    public suspend fun setCacheBudgetBytes(value: Long) {
        val clamped = value.coerceIn(MIN_CACHE_BUDGET_BYTES, MAX_CACHE_BUDGET_BYTES)
        store.edit { it[KEY_CACHE_BUDGET_BYTES] = clamped }
    }

    /** `preferredItag`, default 251 (audio allow-list lives in `:core:youtube`). */
    public val preferredItag: Flow<Int> =
        store.data.map { it[KEY_PREFERRED_ITAG] ?: DEFAULT_PREFERRED_ITAG }

    public suspend fun setPreferredItag(itag: Int) {
        store.edit { it[KEY_PREFERRED_ITAG] = itag }
    }

    /**
     * `videoItag` — the user's video-quality pick as an adaptive mp4 ladder
     * itag (`137`/`136`/`135`/`134`/`133`/`160`). Absent ⇒ auto (best
     * adaptive). Consumed by `:core:media`'s `VideoQualityStore`, which owns
     * the in-memory mirror the data-source stack reads per resolve.
     */
    public val videoItag: Flow<Int?> =
        store.data.map { it[KEY_VIDEO_ITAG] }

    public suspend fun setVideoItag(itag: Int?) {
        store.edit {
            if (itag == null) it.remove(KEY_VIDEO_ITAG) else it[KEY_VIDEO_ITAG] = itag
        }
    }

    /** `crossfadeMs`, default 2000. */
    public val crossfadeMs: Flow<Int> =
        store.data.map { it[KEY_CROSSFADE_MS] ?: DEFAULT_CROSSFADE_MS }

    public suspend fun setCrossfadeMs(value: Int) {
        store.edit { it[KEY_CROSSFADE_MS] = value }
    }

    /**
     * `taskRemovalPolicy(STOP|PAUSE)`, default STOP (C1/C4). Consumed by Phase 2's
     * `PlaybackService.onTaskRemoved`: STOP ⇒ `pauseAllPlayersAndStopSelf()`;
     * PAUSE ⇒ keep playing. Toggled in Settings (Phase 4).
     */
    public val taskRemovalPolicy: Flow<TaskRemovalPolicy> =
        store.data.map { prefs ->
            prefs[KEY_TASK_REMOVAL_POLICY]
                ?.let { runCatching { TaskRemovalPolicy.valueOf(it) }.getOrNull() }
                ?: TaskRemovalPolicy.valueOf(DEFAULT_TASK_REMOVAL_POLICY)
        }

    public suspend fun setTaskRemovalPolicy(policy: TaskRemovalPolicy) {
        store.edit { it[KEY_TASK_REMOVAL_POLICY] = policy.name }
    }

    // --- library -------------------------------------------------------------

    /** `libraryRootTreeUri` — the single SAF tree URI (multi-tree deferred, D13). */
    public val libraryRootTreeUri: Flow<String?> =
        store.data.map { it[KEY_LIBRARY_ROOT_TREE_URI] }

    public suspend fun setLibraryRootTreeUri(treeUri: String?) {
        store.edit {
            if (treeUri == null) it.remove(KEY_LIBRARY_ROOT_TREE_URI)
            else it[KEY_LIBRARY_ROOT_TREE_URI] = treeUri
        }
    }

    /** `libraryLastScanAt` — epoch millis of the last completed scan, or null. */
    public val libraryLastScanAt: Flow<Long?> =
        store.data.map { it[KEY_LIBRARY_LAST_SCAN_AT] }

    public suspend fun setLibraryLastScanAt(epochMs: Long?) {
        store.edit {
            if (epochMs == null) it.remove(KEY_LIBRARY_LAST_SCAN_AT)
            else it[KEY_LIBRARY_LAST_SCAN_AT] = epochMs
        }
    }

    // --- appearance ------------------------------------------------------------

    /** `themeMode(SYSTEM|LIGHT|DARK)`, default SYSTEM. */
    public val themeMode: Flow<ThemeMode> =
        store.data.map { prefs ->
            prefs[KEY_THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.valueOf(DEFAULT_THEME_MODE)
        }

    public suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /** `dynamicColor`, default true. */
    public val dynamicColor: Flow<Boolean> =
        store.data.map { it[KEY_DYNAMIC_COLOR] ?: DEFAULT_DYNAMIC_COLOR }

    public suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    // --- consent domains (SEC-F12/F15) -----------------------------------------

    /** `updaterEnabled`, default false. Gates the updater path only (github dist). */
    public val updaterEnabled: Flow<Boolean> =
        store.data.map { it[KEY_UPDATER_ENABLED] ?: DEFAULT_UPDATER_ENABLED }

    public suspend fun setUpdaterEnabled(enabled: Boolean) {
        store.edit { it[KEY_UPDATER_ENABLED] = enabled }
    }

    /**
     * `remoteConfigEnabled`, default false. Gates the remote player-config
     * registry fetch only; false ⇒ `RemotePlayerConfigStore` emits zero requests.
     */
    public val remoteConfigEnabled: Flow<Boolean> =
        store.data.map { it[KEY_REMOTE_CONFIG_ENABLED] ?: DEFAULT_REMOTE_CONFIG_ENABLED }

    public suspend fun setRemoteConfigEnabled(enabled: Boolean) {
        store.edit { it[KEY_REMOTE_CONFIG_ENABLED] = enabled }
    }

    /** `remoteConfigLastRefreshAt` — epoch millis of the last registry refresh. */
    public val remoteConfigLastRefreshAt: Flow<Long?> =
        store.data.map { it[KEY_REMOTE_CONFIG_LAST_REFRESH_AT] }

    public suspend fun setRemoteConfigLastRefreshAt(epochMs: Long?) {
        store.edit {
            if (epochMs == null) it.remove(KEY_REMOTE_CONFIG_LAST_REFRESH_AT)
            else it[KEY_REMOTE_CONFIG_LAST_REFRESH_AT] = epochMs
        }
    }

    /**
     * `localOnlySearch`, default false (YouTube is a core feature; disclosed at
     * first run). True ⇒ zero `YouTubeClient` calls (leak test, F12).
     */
    public val localOnlySearch: Flow<Boolean> =
        store.data.map { it[KEY_LOCAL_ONLY_SEARCH] ?: DEFAULT_LOCAL_ONLY_SEARCH }

    public suspend fun setLocalOnlySearch(enabled: Boolean) {
        store.edit { it[KEY_LOCAL_ONLY_SEARCH] = enabled }
    }

    /** `sendSearchSuggestions`, default false. */
    public val sendSearchSuggestions: Flow<Boolean> =
        store.data.map { it[KEY_SEND_SEARCH_SUGGESTIONS] ?: DEFAULT_SEND_SEARCH_SUGGESTIONS }

    public suspend fun setSendSearchSuggestions(enabled: Boolean) {
        store.edit { it[KEY_SEND_SEARCH_SUGGESTIONS] = enabled }
    }

    // --- streaming / playback -------------------------------------------------

    /**
     * `audioItagTier(AUTO|LOW|NORMAL|HIGH)`, default AUTO — the YouTube audio
     * quality tier picked in Settings (`LOW` ~48 kbps, `NORMAL` ~128 kbps,
     * `HIGH` ~160 kbps). `:core:media`'s audio ladder maps the tier onto
     * concrete itags at resolve time; AUTO means the allow-list default.
     */
    public val audioItagTier: Flow<String> =
        store.data.map { it[KEY_AUDIO_ITAG_TIER] ?: DEFAULT_AUDIO_ITAG_TIER }

    public suspend fun setAudioItagTier(tier: String) {
        store.edit { it[KEY_AUDIO_ITAG_TIER] = tier }
    }

    /** `monoAudio`, default false — downmix stereo to mono (engine-side). */
    public val monoAudio: Flow<Boolean> =
        store.data.map { it[KEY_MONO_AUDIO] ?: DEFAULT_MONO_AUDIO }

    public suspend fun setMonoAudio(enabled: Boolean) {
        store.edit { it[KEY_MONO_AUDIO] = enabled }
    }

    /**
     * `gapless`, default true — prefer gapless transitions between consecutive
     * queue items when the engine supports it.
     */
    public val gapless: Flow<Boolean> =
        store.data.map { it[KEY_GAPLESS] ?: DEFAULT_GAPLESS }

    public suspend fun setGapless(enabled: Boolean) {
        store.edit { it[KEY_GAPLESS] = enabled }
    }

    /**
     * `normalizeVolume`, default false — loudness normalization across tracks
     * (distinct from ReplayGain preamp). Stored-only until `:core:media`
     * consumes the key.
     */
    public val normalizeVolume: Flow<Boolean> =
        store.data.map { it[KEY_NORMALIZE_VOLUME] ?: DEFAULT_NORMALIZE_VOLUME }

    public suspend fun setNormalizeVolume(enabled: Boolean) {
        store.edit { it[KEY_NORMALIZE_VOLUME] = enabled }
    }

    /**
     * Clears every key — the DataStore half of the canonical
     * `deleteAllLocalData()` action (CC10; the full wipe also releases the SAF
     * grant, drops both DBs, clears media/coil caches and `player_configs.json`,
     * and zeroes the in-memory YouTube token caches — assembled by the caller).
     */
    public suspend fun clear() {
        store.edit { it.clear() }
    }
}
