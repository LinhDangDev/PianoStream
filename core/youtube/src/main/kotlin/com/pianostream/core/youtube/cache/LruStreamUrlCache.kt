package com.pianostream.core.youtube.cache

import android.os.SystemClock
import com.pianostream.core.youtube.api.ResolvedStream
import java.util.LinkedHashMap

/** Default [StreamUrlCache]: synchronized access-order LRU, in-memory only. */
internal class LruStreamUrlCache(
    private val capacity: Int = 256,
    private val freshnessMarginMs: Long = SAFETY_MARGIN_MS,
    private val uptimeMs: () -> Long = SystemClock::elapsedRealtime,
) : StreamUrlCache {

    private data class Key(val videoId: String, val clientId: String, val itag: Int)

    private val map = object : LinkedHashMap<Key, ResolvedStream>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ResolvedStream>) =
            size > capacity
    }

    @Synchronized
    override fun get(
        videoId: String,
        clientId: String,
        itag: Int,
        nowUptimeMs: Long,
    ): ResolvedStream? {
        val key = Key(videoId, clientId, itag)
        val hit = map[key] ?: return null
        // F17: expire inside the 300 s margin counts as stale.
        return if (hit.isFresh(nowUptimeMs, freshnessMarginMs)) hit
        else {
            map.remove(key)
            null
        }
    }

    @Synchronized
    override fun put(stream: ResolvedStream): Boolean {
        // Never admit an entry already inside the safety margin (F17).
        if (!stream.isFresh(uptimeMs(), freshnessMarginMs)) return false
        map[Key(stream.videoId, stream.clientId, stream.itag)] = stream
        return true
    }

    @Synchronized
    override fun evict(videoId: String, clientId: String, itag: Int) {
        map.remove(Key(videoId, clientId, itag))
    }

    @Synchronized
    override fun clear() = map.clear()

    override val size: Int
        @Synchronized get() = map.size

    companion object {
        const val SAFETY_MARGIN_MS = 300_000L
    }
}
