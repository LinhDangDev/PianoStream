package com.pianostream.core.youtube.api

/**
 * Resolves a `videoId` to a playable `googlevideo` URL (Interface Freeze —
 * Phase 1b implements `StreamResolverImpl` with the client fallback chain,
 * cipher solving, poToken minting and the StreamUrlCache).
 *
 * Contract:
 *  - `preferredItag` defaults to `251` (Opus); the audio allow-list order is
 *    `251 → 140 → 250 → 141` and `itag` is validated against it before a
 *    [ResolvedStream] is produced (F11).
 *  - `videoId` must satisfy `^[A-Za-z0-9_-]{11}$` (boundary validation CC3/F11).
 *  - Freshness/caching follow M1+F17 (uptime-clock deadline, `min(expire, 6h)`
 *    clamp, 1 h default, 300 s margin, 403 force-evict).
 *  - `force=true` evicts the cached entry, demotes the client via
 *    `ClientHealthTracker.recordFailure(clientId)` and re-resolves excluding
 *    that client (H1).
 *  - Throws [ExtractionException] on failure — a stale or expired URL is
 *    never returned; `RateLimited` is used for HTTP 429.
 */
public interface StreamResolver {

    public suspend fun resolve(
        videoId: String,
        preferredItag: Int = 251,
        force: Boolean = false,
    ): ResolvedStream

    /**
     * Resolves a `videoId` to an adaptive **video-only** URL
     * (`pianostream://video/<videoId>` playback, merged with a separate audio
     * stream upstream): the best format from the video allow-list — mp4/h264
     * `137` 1080p → `136` 720p → `135` 480p → `134` 360p → `133` 240p →
     * `160` 144p, with webm/vp9 `248`/`247`/`244`/`243`/`242`/`278` as the
     * same-tier fallback. `preferredItag` pins a tier: the pick is the best
     * adaptive format at-or-below it (mp4 preferred per tier); pass
     * [VIDEO_ITAG_AUTO] for the best available adaptive quality. Muxed
     * progressive `22`/`18` are intentionally excluded — the video child must
     * stay video-only so the merged source never doubles audio.
     *
     * Reuses the same client fallback, cipher, poToken and cache machinery as
     * [resolve]; [ExtractionException.NoFormats] when no adaptive video
     * format exists on any client.
     *
     * Default throws `NoFormats` so audio-only implementers (fakes) keep
     * compiling; the error surfaces on the typed error card like any other
     * failed resolve.
     */
    public suspend fun resolveVideo(
        videoId: String,
        preferredItag: Int = VIDEO_ITAG_AUTO,
        force: Boolean = false,
    ): ResolvedStream = throw ExtractionException.NoFormats(
        "video resolve unsupported by this resolver"
    )

    public companion object {
        /**
         * `preferredItag` sentinel for [resolveVideo]: let the resolver pick
         * the best adaptive quality (equivalent to requesting the top of the
         * ladder). Distinct from every real itag so implementations can map
         * it onto their canonical default.
         */
        public const val VIDEO_ITAG_AUTO: Int = 0
    }
}
