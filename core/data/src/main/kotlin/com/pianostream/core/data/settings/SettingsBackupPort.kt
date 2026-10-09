package com.pianostream.core.data.settings

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Backup/restore facade over [SettingsDataStore]'s frozen key surface.
 *
 * `androidx.datastore-preferences` is `implementation`-scoped inside
 * `:core:data`, so `Preferences` types can never appear on a consumer's
 * classpath — this port speaks plain `org.json` instead and drives the
 * store exclusively through its public typed flows/setters, so every
 * restored value lands through the same validation path the UI uses.
 *
 * Exported keys are the portable preferences only. `libraryRootTreeUri` and
 * `libraryLastScanAt` are deliberately NOT portable: the SAF grant they name
 * is issued per-device, so restoring a foreign URI would point the app at a
 * folder it cannot read.
 */
public class SettingsBackupPort @Inject constructor(
    private val settings: SettingsDataStore,
) {

    /** Every portable key as a typed JSON value (string/bool/number). */
    public suspend fun exportJson(): JSONObject = JSONObject().apply {
        put("themeMode", settings.themeMode.first().name)
        put("dynamicColor", settings.dynamicColor.first())
        put("replayGainMode", settings.replayGainMode.first().name)
        put("preampDb", settings.preampDb.first())
        put("cacheBudgetBytes", settings.cacheBudgetBytes.first())
        put("preferredItag", settings.preferredItag.first())
        settings.videoItag.first()?.let { put("videoItag", it) }
        put("crossfadeMs", settings.crossfadeMs.first())
        put("taskRemovalPolicy", settings.taskRemovalPolicy.first().name)
        put("updaterEnabled", settings.updaterEnabled.first())
        put("remoteConfigEnabled", settings.remoteConfigEnabled.first())
        settings.remoteConfigLastRefreshAt.first()
            ?.let { put("remoteConfigLastRefreshAt", it) }
        put("localOnlySearch", settings.localOnlySearch.first())
        put("sendSearchSuggestions", settings.sendSearchSuggestions.first())
        put("audioItagTier", settings.audioItagTier.first())
        put("monoAudio", settings.monoAudio.first())
        put("gapless", settings.gapless.first())
        put("normalizeVolume", settings.normalizeVolume.first())
    }

    /**
     * Applies every known key in [json]; absent keys keep their local value,
     * unknown keys are ignored (forward-compat), and a value that fails the
     * store's own enum/range parsing is skipped rather than aborting the
     * restore.
     */
    public suspend fun importJson(json: JSONObject) {
        json.optString("themeMode", null)?.let { v ->
            runCatching {
                settings.setThemeMode(SettingsDataStore.ThemeMode.valueOf(v))
            }
        }
        if (json.has("dynamicColor")) {
            runCatching { settings.setDynamicColor(json.getBoolean("dynamicColor")) }
        }
        json.optString("replayGainMode", null)?.let { v ->
            runCatching {
                settings.setReplayGainMode(SettingsDataStore.ReplayGainMode.valueOf(v))
            }
        }
        if (json.has("preampDb")) {
            runCatching { settings.setPreampDb(json.getDouble("preampDb")) }
        }
        if (json.has("cacheBudgetBytes")) {
            runCatching { settings.setCacheBudgetBytes(json.getLong("cacheBudgetBytes")) }
        }
        if (json.has("preferredItag")) {
            runCatching { settings.setPreferredItag(json.getInt("preferredItag")) }
        }
        if (json.has("videoItag")) {
            runCatching {
                settings.setVideoItag(
                    if (json.isNull("videoItag")) null else json.getInt("videoItag"),
                )
            }
        }
        if (json.has("crossfadeMs")) {
            runCatching { settings.setCrossfadeMs(json.getInt("crossfadeMs")) }
        }
        json.optString("taskRemovalPolicy", null)?.let { v ->
            runCatching {
                settings.setTaskRemovalPolicy(
                    SettingsDataStore.TaskRemovalPolicy.valueOf(v),
                )
            }
        }
        if (json.has("updaterEnabled")) {
            runCatching { settings.setUpdaterEnabled(json.getBoolean("updaterEnabled")) }
        }
        if (json.has("remoteConfigEnabled")) {
            runCatching {
                settings.setRemoteConfigEnabled(json.getBoolean("remoteConfigEnabled"))
            }
        }
        if (json.has("remoteConfigLastRefreshAt")) {
            runCatching {
                settings.setRemoteConfigLastRefreshAt(
                    if (json.isNull("remoteConfigLastRefreshAt")) {
                        null
                    } else {
                        json.getLong("remoteConfigLastRefreshAt")
                    },
                )
            }
        }
        if (json.has("localOnlySearch")) {
            runCatching { settings.setLocalOnlySearch(json.getBoolean("localOnlySearch")) }
        }
        if (json.has("sendSearchSuggestions")) {
            runCatching {
                settings.setSendSearchSuggestions(json.getBoolean("sendSearchSuggestions"))
            }
        }
        json.optString("audioItagTier", null)?.let { v ->
            runCatching { settings.setAudioItagTier(v) }
        }
        if (json.has("monoAudio")) {
            runCatching { settings.setMonoAudio(json.getBoolean("monoAudio")) }
        }
        if (json.has("gapless")) {
            runCatching { settings.setGapless(json.getBoolean("gapless")) }
        }
        if (json.has("normalizeVolume")) {
            runCatching { settings.setNormalizeVolume(json.getBoolean("normalizeVolume")) }
        }
    }
}
