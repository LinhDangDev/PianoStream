package com.pianostream.core.data.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val KEY_AUDIO_ITAG_TIER = stringPreferencesKey("audioItagTier")

/**
 * `"audioItagTier"` (`AUTO`/`LOW`/`NORMAL`/`HIGH`) read accessor — the
 * Settings "Audio quality" row writes the same key name; absent ⇒ `null`.
 *
 * Extension (not a member) so the frozen [SettingsDataStore] stays untouched.
 * It exposes the raw string so `:core:media` consumes it without needing the
 * datastore preferences artifact on its own compile classpath; enum mapping
 * and unknown-value tolerance live in `AudioQualityStore`.
 */
public val SettingsDataStore.audioItagTier: Flow<String?>
    get() = data.map { it[KEY_AUDIO_ITAG_TIER] }
