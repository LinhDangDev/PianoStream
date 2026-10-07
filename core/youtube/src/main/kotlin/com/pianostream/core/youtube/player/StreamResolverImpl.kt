package com.pianostream.core.youtube.player

import android.os.SystemClock
import com.pianostream.core.data.util.Redact
import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import com.pianostream.core.youtube.cache.StreamUrlCache
import com.pianostream.core.youtube.cipher.CipherSolver
import com.pianostream.core.youtube.cipher.PlayerConfig
import com.pianostream.core.youtube.cipher.PlayerJsFetcher
import com.pianostream.core.youtube.cipher.RemotePlayerConfigStore
import com.pianostream.core.youtube.cipher.SolveRequest
import com.pianostream.core.youtube.client.ClientFallbackStrategy
import com.pianostream.core.youtube.client.ClientHealthTracker
import com.pianostream.core.youtube.client.YouTubeClientCatalog
import com.pianostream.core.youtube.innertube.InnerTubeHeaders
import com.pianostream.core.youtube.potoken.PoTokenBinding
import com.pianostream.core.youtube.potoken.PoTokenProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Orchestrates `videoId → googlevideo URL`: ordered client fallback, cipher
 * solving (QuickJS primary, locked-down-WebView fallback), poToken minting,
 * and the F17-clamped LRU cache.
 *
 * Every failure maps to [ExtractionException] with URL-redacted messages
 * (CC4/F13); nothing stale is ever returned.
 */
internal class StreamResolverImpl(
    private val health: ClientHealthTracker,
    private val fallback: ClientFallbackStrategy,
    private val cipherSolver: CipherSolver,
    private val fallbackSolver: CipherSolver? = null,
    private val poToken: PoTokenProvider? = null,
    private val cache: StreamUrlCache,
    private val configStore: RemotePlayerConfigStore,
    private val playerJs: PlayerJsFetcher,
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val uptimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val signedIn: Boolean = false,
    /** Overridable for MockWebServer tests. */
    private val playerEndpoint: String =
        "https://music.youtube.com/youtubei/v1/player",
) : StreamResolver {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(
        videoId: String,
        preferredItag: Int,
        force: Boolean,
    ): ResolvedStream {
        require(PianoStreamId.isVideoId(videoId)) {
            "videoId must match ^[A-Za-z0-9_-]{11}$"
        }
        if (preferredItag !in FormatPicker.ALLOWED_ITAGS) {
            throw ExtractionException.NoFormats("itag $preferredItag not in audio allow-list")
        }

        var excluding: String? = null
        if (force) {
            // H1: evict the cached entry + demote its client, then re-resolve
            // excluding that client for this cycle.
            excluding = cachedClientFor(videoId, preferredItag)
            excluding?.let {
                health.recordFailure(it)
                cache.evict(videoId, it, preferredItag)
            }
        } else {
            for (client in fallback.allExcept()) {
                cache.get(videoId, client.id, preferredItag, uptimeMs())
                    ?.let { return it }
            }
        }

        val candidates = fallback.orderedCandidates(signedIn, excluding)
        var lastError: ExtractionException? = null
        for (client in candidates) {
            try {
                val stream = resolveWith(client, videoId, preferredItag)
                health.recordSuccess(client.id)
                return stream
            } catch (e: ExtractionException.RateLimited) {
                throw e // 429 → caller backs off; no retry loop (plan R5)
            } catch (e: ExtractionException) {
                health.recordFailure(client.id)
                lastError = e
            }
        }
        throw lastError ?: ExtractionException.AllClientsFailed(
            "no catalog client produced a playable stream"
        )
    }

    private fun cachedClientFor(videoId: String, itag: Int): String? =
        fallback.allExcept()
            .firstOrNull { cache.get(videoId, it.id, itag, uptimeMs()) != null }
            ?.id

    private suspend fun resolveWith(
        client: YouTubeClientCatalog.Client,
        videoId: String,
        preferredItag: Int,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        // Session-bound token on the player request only for clients that
        // require it; GVS stays off this path entirely (F3).
        val requestToken = if (client.requiresGvsPoToken) {
            poToken?.token(PoTokenBinding.VISITOR, InnerTubeHeaders.visitorData)
                ?: throw ExtractionException.PoTokenRequired(client.id)
        } else null

        val body = PlayerRequestBuilder.build(
            client = client,
            videoId = videoId,
            poToken = requestToken,
        )
        val playerResponse = postPlayer(client, body)
        val parsed = PlayerResponseParser.parse(playerResponse, videoId)
        if (parsed.requiresPoToken) throw ExtractionException.PoTokenRequired(client.id)
        if (!parsed.playabilityOk) {
            throw ExtractionException.NoFormats("unplayable on ${client.id}")
        }
        val format = FormatPicker.pick(parsed.formats, preferredItag)
            ?: throw ExtractionException.NoFormats("no allowed audio itag on ${client.id}")

        val finalUrl = materializeUrl(client, playerResponse, format)

        // GVS token decorates only the googlevideo URL (F3 — never the
        // player request path).
        val url = if (client.requiresGvsPoToken) {
            val gvs = poToken?.token(PoTokenBinding.GVS, videoId)
                ?: throw ExtractionException.PoTokenRequired(client.id)
            finalUrl + "&pot=" + gvs
        } else finalUrl

        val nowEpoch = epochMs()
        val nowUptime = uptimeMs()
        val expiresAtEpoch = PlayerResponseParser.clampedExpiry(url, nowEpoch)
        val stream = ResolvedStream(
            videoId = videoId,
            url = url,
            itag = format.itag,
            mimeType = format.mimeType,
            bitrate = format.bitrate,
            contentLength = format.contentLength,
            expiresAtEpochMs = expiresAtEpoch,
            expiresInSeconds = (expiresAtEpoch - nowEpoch) / 1000,
            headers = mapOf(
                "User-Agent" to (client.userAgent ?: InnerTubeHeaders.CLIENT_NAME),
            ),
            clientId = client.id,
            resolvedAtUptimeMs = nowUptime,
            expiresAtUptimeMs = nowUptime + (expiresAtEpoch - nowEpoch),
        )
        if (!PlayerResponseParser.isBelowCacheFloor(url, nowEpoch)) {
            cache.put(stream)
        }
        stream
    }

    private fun postPlayer(
        client: YouTubeClientCatalog.Client,
        body: JsonObject,
    ): JsonObject {
        val req = Request.Builder()
            .url(playerEndpoint)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { client.userAgent?.let { header("User-Agent", it) } }
            .build()
        return try {
            okHttp.newCall(req).execute().use { resp ->
                when {
                    resp.code == 429 -> throw ExtractionException.RateLimited()
                    resp.code == 403 -> throw ExtractionException.Network(
                        "player HTTP 403 on ${client.id}"
                    )
                    !resp.isSuccessful -> throw ExtractionException.Network(
                        "player HTTP ${resp.code} on ${client.id}"
                    )
                    else -> resp.body?.string()
                        ?.let { json.parseToJsonElement(it) as? JsonObject }
                        ?: throw ExtractionException.Network("empty player body")
                }
            }
        } catch (e: IOException) {
            throw ExtractionException.Network("player request failed", e)
        }
    }

    /**
     * Turn a picked format into the final URL: direct `url=` when unsigned,
     * else solve `signatureCipher` (sig + optional `n`) through the bounded
     * solver chain. `pot=` is applied by the caller for GVS clients.
     */
    private suspend fun materializeUrl(
        client: YouTubeClientCatalog.Client,
        playerResponse: JsonObject,
        format: FormatPicker.Format,
    ): String {
        format.url?.let { return it }
        val cipher = format.signatureCipher
            ?: throw ExtractionException.NoFormats("format has neither url nor cipher")

        // signatureCipher is `s=..&sp=sig&url=..` form-urlencoded.
        val params = cipher.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to
                java.net.URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
        }.toMap()
        val rawUrl = params["url"]
            ?: throw ExtractionException.CipherFailure("cipher without url")
        val sParam = params["s"]
            ?: throw ExtractionException.CipherFailure("cipher without s param")
        val sp = params["sp"] ?: "signature"
        val nInUrl = runCatching {
            java.net.URI(rawUrl).rawQuery?.split("&")
                ?.firstOrNull { it.startsWith("n=") }?.substring(2)
        }.getOrNull()

        val playerHash = extractPlayerHash(playerResponse)
        val config = configStore.configFor(playerHash)
            ?: throw ExtractionException.AllClientsFailed(
                "no player config for hash (remote gate closed)"
            )
        val js = playerJs.fetch(config.playerJsUrl ?: defaultPlayerJsUrl(playerHash))
            ?: throw ExtractionException.CipherFailure("player js unavailable")

        val request = SolveRequest(
            playerJs = js,
            config = config,
            signatureCipher = sParam,
            nCipherInput = nInUrl,
        )
        val solved = try {
            cipherSolver.solve(request)
        } catch (e: ExtractionException.CipherFailure) {
            (fallbackSolver?.solve(request))
                ?: throw e // R14: fallback exhausted too
        } catch (e: Throwable) {
            throw ExtractionException.CipherFailure(cause = e)
        }

        val signed = StringBuilder(rawUrl)
        solved.signature?.let { signed.append("&").append(sp).append("=").append(urlEncode(it)) }
        solved.n?.let { n ->
            // Replace or append the n= param with the solved value.
            val u = signed.toString()
            val regex = Regex("([?&])n=[^&]*")
            signed.setLength(0)
            signed.append(
                if (regex.containsMatchIn(u)) regex.replace(u, "$1n=" + urlEncode(n))
                else "$u&n=${urlEncode(n)}"
            )
        }
        return signed.toString()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /** The registry key: `playerHash` inside the response when present, else
     *  the `default` registry entry — community registries key preprocessed
     *  configs by player hash. */
    private fun extractPlayerHash(body: JsonObject): String =
        body["playerHash"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.contentOrNull ?: "default"

    private fun defaultPlayerJsUrl(hash: String): String =
        "https://music.youtube.com/s/player/$hash/base.js"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private object PianoStreamId {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    fun isVideoId(id: String) = VIDEO_ID.matches(id)
}
