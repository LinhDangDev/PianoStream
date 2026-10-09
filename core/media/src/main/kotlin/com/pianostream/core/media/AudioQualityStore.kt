package com.pianostream.core.media

import com.pianostream.core.data.settings.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/**
 * In-memory mirror of the YouTube audio-quality pick (Settings → Audio
 * quality). The data-source stack reads [itag] synchronously at every
 * `pianostream://stream/` resolve, so a mid-playback switch applies on the
 * next media-source prepare.
 *
 * Reads the `"audioItagTier"` string the settings UI persists
 * (`AUTO`/`LOW`/`NORMAL`/`HIGH`) through
 * [SettingsDataStore.audioItagTier]; absent or unknown values read as
 * [AudioQualityTier.AUTO]. Read-only — the write path is owned by the
 * settings surface.
 */
@Singleton
class AudioQualityStore @Inject constructor(
    settings: SettingsDataStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Observable tier for UI surfaces; AUTO when nothing is stored yet. */
    val tier: StateFlow<AudioQualityTier> = settings.audioItagTier
        .map { it.toAudioQualityTier() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = runBlocking {
                settings.audioItagTier.first().toAudioQualityTier()
            },
        )

    /** Preferred audio itag for the `audioItagProvider` seam; `null` = auto. */
    val itag: StateFlow<Int?> = tier
        .map { it.itag }
        .stateIn(scope, SharingStarted.Eagerly, tier.value.itag)

    private fun String?.toAudioQualityTier(): AudioQualityTier =
        this?.let { runCatching { AudioQualityTier.valueOf(it) }.getOrNull() }
            ?: AudioQualityTier.AUTO
}
