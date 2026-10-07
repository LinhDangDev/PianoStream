package com.pianostream.core.youtube.potoken

import android.os.SystemClock

/**
 * Two strictly separate in-memory token caches — visitor-bound and
 * video-bound (GVS) — each with its own expiry. Memory only (CC10): [wipe]
 * zeroes both on DELETE ALL LOCAL DATA.
 */
internal class PoTokenCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val uptimeMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private data class Entry(val token: String, val expiresAtUptimeMs: Long)

    private val visitor = mutableMapOf<String, Entry>()
    private val gvs = mutableMapOf<String, Entry>()

    private fun table(binding: PoTokenBinding) = when (binding) {
        PoTokenBinding.VISITOR -> visitor
        PoTokenBinding.GVS -> gvs
    }

    @Synchronized
    fun put(binding: PoTokenBinding, key: String, token: String) {
        table(binding)[key] = Entry(token, uptimeMs() + ttlMs)
    }

    @Synchronized
    fun get(binding: PoTokenBinding, key: String): String? {
        val e = table(binding)[key] ?: return null
        if (uptimeMs() >= e.expiresAtUptimeMs) {
            table(binding).remove(key)
            return null
        }
        return e.token
    }

    /** CC10: zero both caches. */
    @Synchronized
    fun wipe() {
        visitor.clear()
        gvs.clear()
    }

    @Synchronized
    fun size(binding: PoTokenBinding): Int = table(binding).size

    companion object {
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L // ~80% of the observed ~40 min upstream TTL (M8)
    }
}
