package com.pianostream.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Playback-engine preference snapshot (playbackfx).
 *
 * `monoAudio`, `gapless` and `normalizeVolume` are engine-facing keys owned by
 * the playback spec; they may be absent until the settings UI writes them, so
 * every read defaults defensively. `Preferences` keys and the DataStore type
 * are invisible outside `:core:data` (datastore-preferences is an
 * `implementation` dep), so the mapping lives here and downstream modules
 * consume this primitive-typed flow.
 *
 * The snapshot also carries `preampDb`/`replayGainMode` — the preamp is part of
 * the normalize pipeline, so a single flow keeps every consumer consistent.
 */
public data class PlaybackPrefs(
    val monoAudio: Boolean = DEFAULT_MONO_AUDIO,
    val gapless: Boolean = DEFAULT_GAPLESS,
    val normalizeVolume: Boolean = DEFAULT_NORMALIZE_VOLUME,
    val crossfadeMs: Int = SettingsDataStore.DEFAULT_CROSSFADE_MS,
    val replayGainMode: SettingsDataStore.ReplayGainMode =
        SettingsDataStore.ReplayGainMode.valueOf(SettingsDataStore.DEFAULT_REPLAY_GAIN_MODE),
    val preampDb: Double = SettingsDataStore.DEFAULT_PREAMP_DB,
) {
    public companion object {
        public const val KEY_MONO_AUDIO: String = "monoAudio"
        public const val KEY_GAPLESS: String = "gapless"
        public const val KEY_NORMALIZE_VOLUME: String = "normalizeVolume"
        public const val KEY_CROSSFADE_MS: String = "crossfadeMs"

        public const val DEFAULT_MONO_AUDIO: Boolean = false
        public const val DEFAULT_GAPLESS: Boolean = true
        public const val DEFAULT_NORMALIZE_VOLUME: Boolean = true
    }
}

private val KEY_MONO = booleanPreferencesKey(PlaybackPrefs.KEY_MONO_AUDIO)
private val KEY_GAPLESS = booleanPreferencesKey(PlaybackPrefs.KEY_GAPLESS)
private val KEY_NORMALIZE = booleanPreferencesKey(PlaybackPrefs.KEY_NORMALIZE_VOLUME)
private val KEY_CROSSFADE = intPreferencesKey(PlaybackPrefs.KEY_CROSSFADE_MS)

internal fun playbackPrefsOf(
    prefs: Preferences,
    preampDb: Double,
    replayGainMode: SettingsDataStore.ReplayGainMode,
): PlaybackPrefs = PlaybackPrefs(
    monoAudio = prefs[KEY_MONO] ?: PlaybackPrefs.DEFAULT_MONO_AUDIO,
    gapless = prefs[KEY_GAPLESS] ?: PlaybackPrefs.DEFAULT_GAPLESS,
    normalizeVolume = prefs[KEY_NORMALIZE] ?: PlaybackPrefs.DEFAULT_NORMALIZE_VOLUME,
    crossfadeMs = prefs[KEY_CROSSFADE] ?: SettingsDataStore.DEFAULT_CROSSFADE_MS,
    replayGainMode = replayGainMode,
    preampDb = preampDb,
)

/** Engine-facing playback prefs, one snapshot per DataStore write. */
public val SettingsDataStore.playbackPrefs: Flow<PlaybackPrefs>
    get() = combine(data, preampDb, replayGainMode, ::playbackPrefsOf)
