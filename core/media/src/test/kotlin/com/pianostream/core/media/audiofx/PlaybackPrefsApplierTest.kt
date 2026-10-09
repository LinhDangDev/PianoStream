package com.pianostream.core.media.audiofx

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.settings.PlaybackPrefs
import com.pianostream.core.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Flow collect → state; every DataStore write lands as one snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPrefsApplierTest {

    @Test
    fun collect_updates_state_on_each_emission() = runTest {
        val state = PlaybackFxState()
        val flow = MutableStateFlow(PlaybackPrefs())
        PlaybackPrefsApplier(flow, state).start(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        flow.value = PlaybackPrefs(monoAudio = true, crossfadeMs = 4000)
        assertThat(state.monoEnabled).isTrue()
        assertThat(state.crossfadeMs).isEqualTo(4000)
        flow.value = PlaybackPrefs(normalizeVolume = false)
        assertThat(state.monoEnabled).isFalse()
        assertThat(state.normalizeGainLinear).isEqualTo(1.0f)
    }

    @Test
    fun apply_is_synchronous() {
        val state = PlaybackFxState()
        PlaybackPrefsApplier(
            MutableStateFlow(PlaybackPrefs()),
            state,
        ).apply(PlaybackPrefs(gapless = false, preampDb = -6.0))
        assertThat(state.gaplessEnabled).isFalse()
        assertThat(state.normalizeGainLinear).isWithin(0.001f).of(0.501f)
    }

    @Test
    fun shared_state_starts_at_spec_defaults() {
        assertThat(PlaybackFxState.shared.crossfadeMs)
            .isEqualTo(SettingsDataStore.DEFAULT_CROSSFADE_MS)
        assertThat(PlaybackFxState.shared.monoEnabled).isFalse()
        assertThat(PlaybackFxState.shared.normalizeGainLinear).isEqualTo(1.0f)
    }
}
