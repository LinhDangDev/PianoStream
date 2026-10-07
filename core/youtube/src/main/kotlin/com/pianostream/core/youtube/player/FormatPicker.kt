package com.pianostream.core.youtube.player

/**
 * Audio-itag allow-list and preference order (F11): `251` Opus 160k (WebM)
 * first, then `140` AAC 128k (M4A), then `250`, then `141`.
 *
 * Video allow-list: muxed progressive mp4 only (audio+video in one stream,
 * so `ProgressiveMediaSource` plays it without an extra track) — `22` 720p
 * first, then `18` 360p.
 */
internal object FormatPicker {

    val ALLOWED_ITAGS = listOf(251, 140, 250, 141)
    val ALLOWED_VIDEO_ITAGS = listOf(22, 18)

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
     * Pick the best muxed video format honoring [preferred] first when
     * present, else the canonical order (`22` → `18`). Returns null when no
     * muxed format exists (caller maps to [ExtractionException.NoFormats]).
     */
    fun pickVideo(formats: List<Format>, preferred: Int): Format? {
        val usable = formats.filter { it.itag in ALLOWED_VIDEO_ITAGS }
        if (usable.isEmpty()) return null
        usable.firstOrNull { it.itag == preferred }?.let { return it }
        return ALLOWED_VIDEO_ITAGS.asSequence()
            .mapNotNull { tag -> usable.firstOrNull { it.itag == tag } }
            .firstOrNull()
    }
}
