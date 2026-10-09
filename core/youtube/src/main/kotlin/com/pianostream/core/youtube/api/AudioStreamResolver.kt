package com.pianostream.core.youtube.api

/**
 * Audio-quality-tier resolve seam — the audio mirror of
 * [StreamResolver.resolveVideo]: `preferredItag = null` keeps the canonical
 * audio order (`251 → 140 → 250 → 141`, "Auto"); a non-null tier itag is
 * ordered first with the full ranked allow-list retained as fallback —
 * candidates are never dropped.
 *
 * Lives on a separate interface because [StreamResolver] is frozen; the
 * default delegates to [resolve] so every existing resolver/fake answers
 * tiered audio resolves unchanged.
 */
interface AudioStreamResolver : StreamResolver {

    suspend fun resolveAudio(
        videoId: String,
        preferredItag: Int? = null,
        force: Boolean = false,
    ): ResolvedStream =
        if (preferredItag == null) {
            resolve(videoId, force = force)
        } else {
            resolve(videoId, preferredItag, force)
        }
}
