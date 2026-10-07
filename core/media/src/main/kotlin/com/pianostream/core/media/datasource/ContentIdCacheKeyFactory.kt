package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Cache key == media identity (CC2): `pianostream://stream/<videoId>` URIs key
 * the `SimpleCache` by their mediaId (`itag` never leaves `ResolvedStream`,
 * so the key survives a 251→140 fallback or a `preferredItag` change — B2).
 * `pianostream://local/…` rows return `null` — local FLAC bytes are never
 * written to the streaming cache. (The null must never reach a live
 * `CacheDataSource`: [SchemeRoutingDataSource] routes `local/` URIs around
 * the cache entirely, so this branch documents the contract rather than
 * executing on a hot path.)
 */
class ContentIdCacheKeyFactory : CacheKeyFactory {

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val uri = dataSpec.uri?.toString() ?: return dataSpec.key ?: ""
        // NOTE: `pianostream://local/` URIs never reach this factory —
        // [SchemeRoutingDataSource] routes them around the CacheDataSource
        // entirely (CacheKeyFactory cannot return null in media3 1.11).
        return when {
            uri.startsWith("pianostream://stream/") -> uri
            // Muxed video caches under its own mediaId, same rule as stream.
            uri.startsWith("pianostream://video/") -> uri
            else -> dataSpec.key ?: uri
        }
    }
}
