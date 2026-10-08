package com.pianostream.core.youtube.player

import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.innertube.model.arr
import com.pianostream.core.youtube.innertube.model.long
import com.pianostream.core.youtube.innertube.model.obj
import com.pianostream.core.youtube.innertube.model.str
import kotlinx.serialization.json.JsonObject

/**
 * Parses a `/youtubei/v1/player` response: videoId match check (CC3), format
 * extraction, the F17 `expire=` clamp.
 */
internal object PlayerResponseParser {

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val EXPIRE = Regex("[?&]expire=([0-9]+)")

    const val MAX_CLAMP_MS = 6 * 60 * 60 * 1000L // 6 h
    const val DEFAULT_TTL_MS = 60 * 60 * 1000L // 1 h when expire missing/bad
    const val MIN_TTL_MS = 300 * 1000L // never cache <= now+300s (F17)

    data class Parsed(
        val formats: List<FormatPicker.Format>,
        val playabilityOk: Boolean,
        val requiresPoToken: Boolean,
    )

    /**
     * @throws ExtractionException.Network when the response videoId does not
     *   match the requested one (marks the client unhealthy upstream).
     */
    fun parse(body: JsonObject, expectedVideoId: String): Parsed {
        // Bot-checked responses (LOGIN_REQUIRED et al.) carry no videoDetails
        // at all — the status must be read before demanding the videoId echo.
        val status = body.obj("playabilityStatus")?.str("status")
        val needsLogin = status == "LOGIN_REQUIRED"
        if (status != "OK") {
            return Parsed(emptyList(), playabilityOk = false, requiresPoToken = needsLogin)
        }
        val respVideoId = body.obj("videoDetails")?.str("videoId")
        if (respVideoId == null || !VIDEO_ID.matches(respVideoId) ||
            respVideoId != expectedVideoId
        ) {
            throw ExtractionException.Network(
                "player response videoId mismatch (expected videoId shape)"
            )
        }
        val streaming = body.obj("streamingData")
        val formats = mutableListOf<FormatPicker.Format>()
        for (key in listOf("formats", "adaptiveFormats")) {
            streaming?.arr(key)?.forEach { el ->
                (el as? JsonObject)?.let { f ->
                    val itag = f.long("itag")?.toInt() ?: return@let
                    val url = f.str("url")
                    val sigCipher = f.str("signatureCipher") ?: f.str("cipher")
                    formats += FormatPicker.Format(
                        itag = itag,
                        url = url,
                        signatureCipher = sigCipher,
                        mimeType = f.str("mimeType") ?: "",
                        bitrate = f.long("bitrate")?.toInt() ?: 0,
                        contentLength = f.str("contentLength")?.toLongOrNull() ?: 0L,
                    )
                }
            }
        }
        val ok = status == "OK" && formats.isNotEmpty()
        return Parsed(formats, ok, requiresPoToken = needsLogin)
    }

    /**
     * F17 clamp: `expiresAtEpochMs = min(parsed, nowEpoch + 6h)`;
     * absent/unparseable `expire` → `nowEpoch + 1h`.
     */
    fun clampedExpiry(url: String, nowEpochMs: Long): Long {
        val parsed = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it * 1000 }
        return when {
            parsed == null -> nowEpochMs + DEFAULT_TTL_MS
            else -> minOf(parsed, nowEpochMs + MAX_CLAMP_MS)
        }
    }

    /** F17: a URL whose parsed `expire` is already inside the 300 s margin is
     *  never admitted to the cache (caller decides whether to resolve at all —
     *  using it uncached is still allowed). */
    fun isBelowCacheFloor(url: String, nowEpochMs: Long): Boolean {
        val parsed = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it * 1000 } ?: return false
        return parsed <= nowEpochMs + MIN_TTL_MS
    }
}
