package com.pianostream.core.media.audiofx

import com.pianostream.core.data.settings.PlaybackPrefs
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.media.audio.GainCalculator

/**
 * Volatile in-memory mirror of [PlaybackPrefs], read synchronously by the
 * audio-processor chain on the player thread (the same pattern
 * `VideoQualityStore` uses for the data-source stack). Audio processors are
 * constructed inside `DefaultAudioSink`, so they cannot receive DI — they
 * read [shared], which [PlaybackPrefsApplier] keeps current.
 *
 * Applied-live vs next-configure:
 *
 *  - [normalizeGainLinear] is read per input buffer → takes effect
 *    immediately (next queued PCM block).
 *  - [monoEnabled] is read inside `onConfigure` → takes effect on the next
 *    audio-sink reconfiguration, i.e. the next track boundary or seek.
 *  - [gaplessEnabled] / [crossfadeMs] are observability mirrors: ExoPlayer
 *    playlist transitions are gapless by default (nothing in the engine
 *    disables it) and `CrossfadeController` reads the store itself before
 *    every fade — both are already correct without processor involvement.
 */
class PlaybackFxState {

    /** Mono downmix flag — consumed by `MonoAudioProcessor.onConfigure`. */
    @Volatile
    var monoEnabled: Boolean = PlaybackPrefs.DEFAULT_MONO_AUDIO
        private set

    /**
     * Linear multiplier for the preamp stage — unity when `normalizeVolume`
     * is off or `replayGainMode` is OFF (the preamp rides the normalize
     * pipeline; off ⇒ no preamp either).
     */
    @Volatile
    var normalizeGainLinear: Float = 1.0f
        private set

    /**
     * Master normalize switch — the preamp stage already honours it via
     * [normalizeGainLinear]; a future per-track ReplayGain writer should
     * gate its gain on this too (see INTEGRATION in the playbackfx report).
     */
    @Volatile
    var normalizeVolume: Boolean = PlaybackPrefs.DEFAULT_NORMALIZE_VOLUME
        private set

    @Volatile
    var gaplessEnabled: Boolean = PlaybackPrefs.DEFAULT_GAPLESS
        private set

    @Volatile
    var crossfadeMs: Int = SettingsDataStore.DEFAULT_CROSSFADE_MS
        private set

    fun update(prefs: PlaybackPrefs) {
        monoEnabled = prefs.monoAudio
        gaplessEnabled = prefs.gapless
        normalizeVolume = prefs.normalizeVolume
        crossfadeMs = prefs.crossfadeMs
        normalizeGainLinear =
            if (prefs.normalizeVolume &&
                prefs.replayGainMode != SettingsDataStore.ReplayGainMode.OFF
            ) {
                GainCalculator.linearGain(
                    gainDb = null,
                    peak = null,
                    preampDb = prefs.preampDb,
                )
            } else {
                1.0f
            }
    }

    companion object {
        /** Process-wide mirror — the applier writes, the processors read. */
        val shared: PlaybackFxState = PlaybackFxState()
    }
}
