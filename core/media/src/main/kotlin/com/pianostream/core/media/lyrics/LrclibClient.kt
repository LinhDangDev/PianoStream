package com.pianostream.core.media.lyrics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * LRClib fetch — `GET {base}/api/get?track_name&artist_name&duration`.
 *
 * Returns the RAW lyric payload ([pickLyricsBody] prefers `syncedLyrics`
 * over `plainLyrics`); 404s, non-2xx, transport failures and malformed
 * bodies all collapse to `null` so callers can treat fetch as best-effort.
 * [baseUrl]/[io] are injectable for MockWebServer tests — no real network.
 */
class LrclibClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://lrclib.net".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteLrcSource {

    override suspend fun fetch(query: LyricsQuery): String? = withContext(io) {
        val url = baseUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("get")
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist)
            .addQueryParameter("duration", (query.durationMs / 1_000L).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PianoStream/1.0 (github.com/LinhDangDev/PianoStream)")
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::pickLyricsBody)
            }
        }.getOrNull()
    }

    /**
     * `syncedLyrics` when present (a whole `.lrc` payload), else
     * `plainLyrics`; both absent/null/blank → null. Pure mapping — tests
     * drive it with a fixture string.
     */
    internal fun pickLyricsBody(body: String): String? {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        for (key in listOf("syncedLyrics", "plainLyrics")) {
            val value = if (obj.isNull(key)) null else obj.optString(key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }
}
