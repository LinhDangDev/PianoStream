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
     * Resolves a `videoId` to a muxed progressive video URL
     * (`pianostream://video/<videoId>` playback): the best format from the
     * video allow-list (`22` 720p mp4 → `18` 360p mp4). Reuses the same
     * client fallback, cipher, poToken and cache machinery as [resolve];
     * [ExtractionException.NoFormats] when no muxed format exists.
     *
     * Default throws `NoFormats` so audio-only implementers (fakes) keep
     * compiling; the error surfaces on the typed error card like any other
     * failed resolve.
     */
    public suspend fun resolveVideo(
        videoId: String,
        force: Boolean = false,
    ): ResolvedStream = throw ExtractionException.NoFormats(
        "video resolve unsupported by this resolver"
    )
}
