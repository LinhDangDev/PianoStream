package com.pianostream.core.media.audiofx

import android.content.Context
import com.pianostream.core.data.settings.PlaybackPrefs
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.data.settings.playbackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [SettingsDataStore.playbackPrefs] into [PlaybackFxState] so the
 * audio-processor chain sees preference changes.
 *
 * `MediaModule` is frozen (owned by another feature), so no DI binding can
 * be added for this class — instead `PlayerFactory.renderersFactory`
 * self-starts it via [ensureStarted] the first time a player sink is built,
 * and a single process-wide scope keeps exactly one collector alive for
 * the app's lifetime (the DataStore flow itself is the cheap part — one
 * file watch).
 *
 * Runtime-applied: `monoAudio` (next sink configure — next track/seek) and
 * `normalizeVolume` + `preampDb` (next PCM block). `gapless` and
 * `crossfadeMs` are already honoured elsewhere: ExoPlayer playlist
 * transitions are gapless by default and `CrossfadeController` reads
 * `crossfadeMs` from the store before every fade, so the applier only
 * mirrors them into [PlaybackFxState] for observability — no further
 * wiring.
 */
class PlaybackPrefsApplier(
    private val prefsFlow: Flow<PlaybackPrefs>,
    private val state: PlaybackFxState = PlaybackFxState.shared,
) {

    /** Starts collecting; returns the collect [Job]. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        prefsFlow.collect { prefs -> apply(prefs) }
    }

    /** Applies one snapshot synchronously — the collect loop calls this. */
    fun apply(prefs: PlaybackPrefs) {
        state.update(prefs)
    }

    companion object {
        private val lock = Any()
        private var started = false

        private val processScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Idempotent process-wide start — safe to call from every
         * RenderersFactory/player build; only the first call launches the
         * collect.
         */
        fun ensureStarted(
            context: Context,
            state: PlaybackFxState = PlaybackFxState.shared,
        ) {
            synchronized(lock) {
                if (started) return
                started = true
            }
            PlaybackPrefsApplier(
                prefsFlow = SettingsDataStore
                    .default(context.applicationContext)
                    .playbackPrefs,
                state = state,
            ).start(processScope)
        }
    }
}
