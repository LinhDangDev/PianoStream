package com.pianostream.core.youtube.api

/**
 * A resolved playable stream (Interface Freeze — CC2, CC4, F17, M1).
 *
 * The `pianostream://stream/<videoId>` mediaId stays transport-agnostic: `itag`
 * lives here only, so itag changes/fallbacks never orphan queue rows (B2).
 *
 * Freshness (M1/F17):
 *  - [expiresAtEpochMs] is derived from the URL `expire=` param with the local
 *    clamp `min(parsed, now + 6h)`; absent/unparseable `expire` ⇒ `now + 1h`.
 *    It is wall-clock for *display* only.
 *  - The freshness deadline actually consulted by the cache/datasource is
 *    [expiresAtUptimeMs], derived from `SystemClock.elapsedRealtime()` at
 *    resolve time ([resolvedAtUptimeMs]) — never the wall clock, so clock
 *    skew/suspend can never make a dead URL look fresh.
 *  - The resolver never caches an entry whose parsed `expire <= now + 300 s`
 *    and refreshes with a 300 s margin; the first 403 on a URL force-evicts
 *    the cache key and bypasses TTL.
 *
 * Redaction (CC4): [toString] prints only `videoId`/`itag`/`clientId`/
 * `expiresAtEpochMs` — never `url` (it carries `pot`/`sig`/`n`) or `headers`.
 * A freeze test asserts no `pot=`/`sig=`/`n=` leak.
 */
public data class ResolvedStream(
    val videoId: String,
    val url: String,
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long,
    val expiresAtEpochMs: Long,
    val expiresInSeconds: Long,
    val headers: Map<String, String>,
    val clientId: String,
    /** `SystemClock.elapsedRealtime()` when the URL was minted (M1). */
    val resolvedAtUptimeMs: Long,
    /** Freshness deadline on the uptime clock (already clamped per F17). */
    val expiresAtUptimeMs: Long,
) {

    /**
     * True while the URL is fresh on the uptime clock, honoring the 300 s
     * refresh margin (M1: `elapsedRealtime()` comparisons only).
     */
    public fun isFresh(nowUptimeMs: Long, marginMs: Long = 300_000L): Boolean =
        nowUptimeMs + marginMs < expiresAtUptimeMs

    /** CC4 — prints only the safe fields; never the URL or headers. */
    override fun toString(): String =
        "ResolvedStream(videoId=$videoId, itag=$itag, clientId=$clientId, " +
            "expiresAtEpochMs=$expiresAtEpochMs)"
}
