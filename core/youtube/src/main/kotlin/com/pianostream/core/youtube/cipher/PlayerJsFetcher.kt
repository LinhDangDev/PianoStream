package com.pianostream.core.youtube.cipher

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the (EJS-preprocessed) player js for a given player hash. In-memory
 * cache keyed by hash; youtube hosts only. Returns null on any failure — the
 * caller degrades via [CipherSolver] fallback or client retry, never retries
 * in a loop here.
 */
internal class PlayerJsFetcher(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val maxBytes: Int = 4 * 1024 * 1024,
) {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }
        if (!url.startsWith("https://")) return@withContext null
        try {
            okHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.size > maxBytes) return@withContext null
                bytes.decodeToString().also { cache[url] = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun put(url: String, js: String) {
        cache[url] = js
    }

    fun clear() = cache.clear()
}
