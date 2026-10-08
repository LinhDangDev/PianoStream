package com.pianostream.core.youtube.player

/**
 * Audio-itag allow-list and preference order (F11): `251` Opus 160k (WebM)
 * first, then `140` AAC 128k (M4A), then `250`, then `141`.
 *
 * Video allow-list: adaptive **video-only** itags only — `pianostream://video/`
 * plays through a `MergingMediaSource` whose video child must carry no audio
 * track, so muxed `22`/`18` are deliberately excluded (a muxed child would
 * double audio beside the separate audio child). Ladder order is per tier:
 * mp4/h264 preferred (decoder compat), webm/vp9 as the same-tier fallback —
 * `137`/`248` 1080p → `136`/`247` 720p → `135`/`244` 480p → `134`/`243` 360p
 * → `133`/`242` 240p → `160`/`278` 144p. [MUXED_VIDEO_ITAGS] documents the
 * progressive set for reference only; it is never picked for video playback.
 */
internal object FormatPicker {

    val ALLOWED_ITAGS = listOf(251, 140, 250, 141)

    /** Muxed progressive mp4 (audio+video in one stream) — reference only. */
    val MUXED_VIDEO_ITAGS = listOf(22, 18)

    /** Adaptive video-only itags, mp4/h264 ladder first then webm/vp9. */
    val ADAPTIVE_VIDEO_ITAGS_MP4 = listOf(137, 136, 135, 134, 133, 160)
    val ADAPTIVE_VIDEO_ITAGS_WEBM = listOf(248, 247, 244, 243, 242, 278)
    val ALLOWED_VIDEO_ITAGS = ADAPTIVE_VIDEO_ITAGS_MP4 + ADAPTIVE_VIDEO_ITAGS_WEBM

    /** Quality tiers descending: (height, mp4 itag, webm itag). */
    private val VIDEO_TIERS = listOf(
        Triple(1080, 137, 248),
        Triple(720, 136, 247),
        Triple(480, 135, 244),
        Triple(360, 134, 243),
        Triple(240, 133, 242),
        Triple(144, 160, 278),
    )

    private val HEIGHT_BY_ITAG: Map<Int, Int> =
        VIDEO_TIERS.flatMap { (h, mp4, webm) -> listOf(mp4 to h, webm to h) }.toMap()

    /** Vertical resolution an adaptive video itag maps to, null when not one. */
    fun heightOf(itag: Int): Int? = HEIGHT_BY_ITAG[itag]

    data class Format(
        val itag: Int,
        val url: String?,
        val signatureCipher: String?,
        val mimeType: String,
        val bitrate: Int,
        val contentLength: Long,
    )

    /**
     * Pick the best allowed format honoring [preferred] first when present in
     * the list, else the canonical order. Returns null when no allowed audio
     * format exists (caller maps to [ExtractionException.NoFormats]).
     */
    fun pick(formats: List<Format>, preferred: Int): Format? {
        val usable = formats.filter { it.itag in ALLOWED_ITAGS }
        if (usable.isEmpty()) return null
        usable.firstOrNull { it.itag == preferred }?.let { return it }
        return ALLOWED_ITAGS.asSequence()
            .mapNotNull { tag -> usable.firstOrNull { it.itag == tag } }
            .firstOrNull()
    }

    /**
     * Pick the adaptive video format for [preferred] (a ladder itag — the
     * caller maps "auto" to the top rung): an exact itag match wins, else the
     * highest available format at-or-below the preferred tier, mp4 before
     * webm within a tier. Never upgrades past the requested tier; when every
     * adaptive format sits above it the lowest available quality is returned
     * instead of failing (the quality menu is static and cannot hide missing
     * tiers). Returns null when no adaptive video format exists (caller maps
     * to [ExtractionException.NoFormats]).
     */
    fun pickVideo(formats: List<Format>, preferred: Int): Format? {
        val usable = formats.filter { it.itag in ALLOWED_VIDEO_ITAGS }
        if (usable.isEmpty()) return null
        usable.firstOrNull { it.itag == preferred }?.let { return it }
        val ceiling = HEIGHT_BY_ITAG[preferred] ?: HEIGHT_BY_ITAG.values.max()
        VIDEO_TIERS.asSequence()
            .filter { it.first <= ceiling }
            .flatMap { sequenceOf(it.second, it.third) }
            .mapNotNull { tag -> usable.firstOrNull { it.itag == tag } }
            .firstOrNull()?.let { return it }
        // Everything usable is above the requested tier — lowest quality is
        // the closest match. mp4 wins height ties.
        return usable.minWithOrNull(
            compareBy(
                { HEIGHT_BY_ITAG[it.itag] ?: Int.MAX_VALUE },
                { if (it.itag in ADAPTIVE_VIDEO_ITAGS_MP4) 0 else 1 },
            ),
        )
    }
}
