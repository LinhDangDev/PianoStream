package com.pianostream.core.youtube.potoken

/**
 * poToken minting contract (internal to `:core:youtube` — not in the freeze
 * set). Two binding domains, two in-memory caches, zeroed together on
 * DELETE ALL LOCAL DATA (CC10).
 *
 *  - a [PoTokenBinding.GVS] token is never read by the player-request path —
 *    it exists only to decorate the final `googlevideo` URL with `pot=`;
 *  - minting is lazy (first resolve, never app start) and ≤8 s (M8);
 *  - refresh happens at ~80% of TTL via the cache expiry, not a timer.
 */
internal interface PoTokenProvider {
    /** Cached token if present+unexpired, else mint, cache, return. */
    suspend fun token(binding: PoTokenBinding, key: String): String?
    fun cached(binding: PoTokenBinding, key: String): String?
    fun wipe()
}
