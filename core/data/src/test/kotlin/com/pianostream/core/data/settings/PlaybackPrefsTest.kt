package com.pianostream.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Defaults + key mapping for the playbackfx snapshot. */
class PlaybackPrefsTest {

    @Test
    fun absent_keys_yield_spec_defaults() {
        val prefs = playbackPrefsOf(
            prefs = emptyPreferences(),
            preampDb = 0.0,
            replayGainMode = SettingsDataStore.ReplayGainMode.TRACK,
        )
        assertThat(prefs.monoAudio).isFalse()
        assertThat(prefs.gapless).isTrue()
        assertThat(prefs.normalizeVolume).isTrue()
        assertThat(prefs.crossfadeMs)
            .isEqualTo(SettingsDataStore.DEFAULT_CROSSFADE_MS)
        assertThat(prefs.replayGainMode)
            .isEqualTo(SettingsDataStore.ReplayGainMode.TRACK)
        assertThat(prefs.preampDb).isEqualTo(0.0)
    }

    @Test
    fun stored_keys_map_through() {
        val prefs = playbackPrefsOf(
            prefs = preferencesOf(
                booleanPreferencesKey("monoAudio") to true,
                booleanPreferencesKey("gapless") to false,
                booleanPreferencesKey("normalizeVolume") to false,
                intPreferencesKey("crossfadeMs") to 6500,
            ),
            preampDb = 3.5,
            replayGainMode = SettingsDataStore.ReplayGainMode.ALBUM,
        )
        assertThat(prefs.monoAudio).isTrue()
        assertThat(prefs.gapless).isFalse()
        assertThat(prefs.normalizeVolume).isFalse()
        assertThat(prefs.crossfadeMs).isEqualTo(6500)
        assertThat(prefs.replayGainMode)
            .isEqualTo(SettingsDataStore.ReplayGainMode.ALBUM)
        assertThat(prefs.preampDb).isEqualTo(3.5)
    }

    @Test
    fun partial_keys_keep_remaining_defaults() {
        val prefs = playbackPrefsOf(
            prefs = preferencesOf(booleanPreferencesKey("monoAudio") to true),
            preampDb = -2.0,
            replayGainMode = SettingsDataStore.ReplayGainMode.OFF,
        )
        assertThat(prefs.monoAudio).isTrue()
        assertThat(prefs.gapless).isTrue()
        assertThat(prefs.normalizeVolume).isTrue()
        assertThat(prefs.crossfadeMs)
            .isEqualTo(SettingsDataStore.DEFAULT_CROSSFADE_MS)
        assertThat(prefs.replayGainMode)
            .isEqualTo(SettingsDataStore.ReplayGainMode.OFF)
        assertThat(prefs.preampDb).isEqualTo(-2.0)
    }
}
