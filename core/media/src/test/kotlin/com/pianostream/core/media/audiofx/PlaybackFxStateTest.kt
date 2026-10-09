package com.pianostream.core.media.audiofx

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.settings.PlaybackPrefs
import com.pianostream.core.data.settings.SettingsDataStore
import org.junit.Test

/** prefs → volatile engine-state mapping. */
class PlaybackFxStateTest {

    private val state = PlaybackFxState()

    @Test
    fun defaults_map_through_unchanged() {
        state.update(PlaybackPrefs())
        assertThat(state.monoEnabled).isFalse()
        assertThat(state.gaplessEnabled).isTrue()
        assertThat(state.normalizeVolume).isTrue()
        assertThat(state.crossfadeMs)
            .isEqualTo(SettingsDataStore.DEFAULT_CROSSFADE_MS)
        assertThat(state.normalizeGainLinear).isEqualTo(1.0f)
    }

    @Test
    fun mono_and_normalize_flags_follow_prefs() {
        state.update(PlaybackPrefs(monoAudio = true, normalizeVolume = false))
        assertThat(state.monoEnabled).isTrue()
        assertThat(state.normalizeVolume).isFalse()
        // normalize off ⇒ preamp stage forced to unity.
        assertThat(state.normalizeGainLinear).isEqualTo(1.0f)
    }

    @Test
    fun preamp_applies_when_normalize_on_and_gain_mode_live() {
        state.update(PlaybackPrefs(normalizeVolume = true, preampDb = 6.0))
        // 10^(6/20) ≈ 1.995
        assertThat(state.normalizeGainLinear).isWithin(0.001f).of(1.995f)
    }

    @Test
    fun preamp_forced_to_unity_when_replaygain_off() {
        state.update(
            PlaybackPrefs(
                normalizeVolume = true,
                preampDb = 6.0,
                replayGainMode = SettingsDataStore.ReplayGainMode.OFF,
            ),
        )
        assertThat(state.normalizeGainLinear).isEqualTo(1.0f)
    }

    @Test
    fun negative_preamp_attenuates() {
        state.update(PlaybackPrefs(preampDb = -12.0))
        assertThat(state.normalizeGainLinear).isWithin(0.001f).of(0.251f)
    }

    @Test
    fun gapless_and_crossfade_mirror_for_observability() {
        state.update(PlaybackPrefs(gapless = false, crossfadeMs = 9000))
        assertThat(state.gaplessEnabled).isFalse()
        assertThat(state.crossfadeMs).isEqualTo(9000)
    }
}
