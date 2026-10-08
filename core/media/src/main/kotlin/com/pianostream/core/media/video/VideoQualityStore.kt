package com.pianostream.core.media.video

import com.pianostream.core.data.settings.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Current YouTube video-quality pick (adaptive mp4 ladder itag, `null` =
 * auto). The data-source stack reads [currentItag] synchronously at every
 * `pianostream://video/` resolve, so a mid-playback switch applies on the
 * next media-source prepare; the selection persists through
 * [SettingsDataStore.videoItag].
 *
 * Values are adaptive video-only itags only — the video child of the merged
 * source must never be muxed (double audio). The UI renders [TIERS].
 */
@Singleton
class VideoQualityStore private constructor(
    initial: Int?,
    private val persist: suspend (Int?) -> Unit,
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(
        settings: SettingsDataStore,
        scope: CoroutineScope,
    ) : this(
        initial = runBlocking { settings.videoItag.first() },
        persist = { itag -> settings.setVideoItag(itag) },
        scope = scope,
    )

    /** In-memory variant — unit tests and previews only (nothing persists). */
    constructor(initial: Int? = null) : this(
        initial = initial,
        persist = {},
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val _itag = MutableStateFlow(initial)

    /** Observable tier for the player UI. */
    val itag: StateFlow<Int?> = _itag.asStateFlow()

    /** Synchronous read for the `videoItagProvider` seam — `null` = auto. */
    val currentItag: Int? get() = _itag.value

    fun set(itag: Int?) {
        _itag.value = itag
        scope.launch { persist(itag) }
    }

    companion object {
        /** Menu order: label → itag (`null` = auto). mp4 ladder only; the
         *  webm rungs stay an internal resolver fallback. */
        val TIERS: List<Pair<String, Int?>> = listOf(
            "Auto" to null,
            "1080p" to 137,
            "720p" to 136,
            "480p" to 135,
            "360p" to 134,
            "240p" to 133,
            "144p" to 160,
        )

        private val HEIGHT_BY_ITAG = mapOf(
            137 to 1080, 248 to 1080,
            136 to 720, 247 to 720,
            135 to 480, 244 to 480,
            134 to 360, 243 to 360,
            133 to 240, 242 to 240,
            160 to 144, 278 to 144,
        )

        /** Chip label for a stored itag — webm rungs map to their tier. */
        fun labelFor(itag: Int?): String =
            itag?.let { HEIGHT_BY_ITAG[it]?.let { h -> "${h}p" } } ?: "Auto"
    }
}
