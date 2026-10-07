package com.pianostream.core.youtube.cache

import com.pianostream.core.youtube.api.ResolvedStream

/**
 * In-memory LRU of resolved stream URLs, keyed `(videoId, clientId, itag)`.
 *
 * F17 rules baked in:
 *  - entries carry the clamped expiry from [ResolvedStream] (`min(parsed,
 *    now+6h)`; missing/unparseable `expire` → `now+1h`) computed at parse time;
 *  - a URL whose parsed `expire` is `<= now+300 s` is never admitted;
 *  - reads evict entries inside the 300 s safety margin;
 *  - the first 403 on a URL force-evicts the key and bypasses TTL — the caller
 *    must re-resolve, never serve stale.
 */
internal interface StreamUrlCache {
    fun get(videoId: String, clientId: String, itag: Int, nowUptimeMs: Long): ResolvedStream?
    fun put(stream: ResolvedStream): Boolean
    fun evict(videoId: String, clientId: String, itag: Int)
    fun clear()
    val size: Int
}
