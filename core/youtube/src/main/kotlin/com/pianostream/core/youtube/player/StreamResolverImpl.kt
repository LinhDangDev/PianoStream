package com.pianostream.core.youtube.player

import android.os.SystemClock
import com.pianostream.core.data.util.Redact
import com.pianostream.core.youtube.api.AudioStreamResolver
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
    /**
     * Overridable for MockWebServer tests. `null` (production) selects the
     * endpoint per client: mobile clients post to `youtubei.googleapis.com`,
     * web clients to `music.youtube.com`. A non-null value is used for every
     * client and also disables the real `visitorData` fetch, so unit tests
     * never touch the network.
     */
    private val playerEndpoint: String? = null,
) : AudioStreamResolver {

    private val json = Json { ignoreUnknownKeys = true }

    /** Which format family a resolve targets; everything else is shared. */
    private enum class Kind(val allowedItags: List<Int>, val label: String) {
        AUDIO(FormatPicker.ALLOWED_ITAGS, "audio"),
        VIDEO(FormatPicker.ALLOWED_VIDEO_ITAGS, "video"),
    }

    override suspend fun resolve(
        videoId: String,
        preferredItag: Int,
        force: Boolean,
    ): ResolvedStream = resolveKinded(videoId, preferredItag, force, Kind.AUDIO)

    override suspend fun resolveVideo(
        videoId: String,
        preferredItag: Int,
        force: Boolean,
    ): ResolvedStream = resolveKinded(
        videoId,
        if (preferredItag == StreamResolver.VIDEO_ITAG_AUTO) {
            FormatPicker.ALLOWED_VIDEO_ITAGS.first()
        } else {
            preferredItag
        },
        force,
        Kind.VIDEO,
    )

    override suspend fun resolveAudio(
        videoId: String,
        preferredItag: Int?,
        force: Boolean,
    ): ResolvedStream = resolveKinded(
        videoId,
        preferredItag ?: FormatPicker.ALLOWED_ITAGS.first(),
        force,
        Kind.AUDIO,
    )

    private suspend fun resolveKinded(
        videoId: String,
        preferredItag: Int,
        force: Boolean,
        kind: Kind,
    ): ResolvedStream {
        require(PianoStreamId.isVideoId(videoId)) {
            "videoId must match ^[A-Za-z0-9_-]{11}$"
        }
        if (preferredItag !in kind.allowedItags) {
            throw ExtractionException.NoFormats(
                "itag $preferredItag not in ${kind.label} allow-list"
            )
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
        logi(TAG, "resolve $videoId (${kind.label}) via ${candidates.map { it.id }}")
        var lastError: ExtractionException? = null
        for (client in candidates) {
            try {
                val stream = resolveWith(client, videoId, preferredItag, kind)
                health.recordSuccess(client.id)
                logi(
                    TAG,
                    "${client.id} resolved itag=${stream.itag} " +
                        "url=${stream.url.take(80)}…",
                )
                return stream
            } catch (e: ExtractionException.RateLimited) {
                throw e // 429 → caller backs off; no retry loop (plan R5)
            } catch (e: ExtractionException) {
                health.recordFailure(client.id)
                logw(
                    TAG,
                    "${client.id} failed: ${e.javaClass.simpleName} ${e.message}",
                )
                lastError = e
            } catch (t: Throwable) {
                loge(TAG, "${client.id} threw ${t.javaClass.name}")
                throw t
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
        kind: Kind,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        // Session-bound poToken rides only the WEB_REMIX request (F3/GVS
        // pairing). Mobile clients return unsigned URLs without one, and a
        // token bound to a visitor the request didn't use can only hurt.
        val requestToken = if (client.requiresGvsPoToken) {
            poToken?.token(
                PoTokenBinding.VISITOR,
                InnerTubeHeaders.visitorData,
            ) ?: throw ExtractionException.PoTokenRequired(client.id)
        } else {
            null
        }

        // Mobile clients need a REAL visitorData (fetched per-client from
        // /visitor_id and cached for the session); a synthetic one reads as
        // a bot and earns SABR-only or bot-checked responses.
        val visitor = visitorDataFor(client)
        val cpn = randomNonce(16)

        val body = PlayerRequestBuilder.build(
            client = client,
            videoId = videoId,
            visitorData = visitor,
            cpn = cpn,
            poToken = requestToken,
        )
        val playerResponse = postPlayer(client, body)
        val parsed = PlayerResponseParser.parse(playerResponse, videoId)
        if (parsed.requiresPoToken) throw ExtractionException.PoTokenRequired(client.id)
        if (!parsed.playabilityOk) {
            throw ExtractionException.NoFormats("unplayable on ${client.id}")
        }
        val format = when (kind) {
            Kind.AUDIO -> FormatPicker.pick(parsed.formats, preferredItag)
            Kind.VIDEO -> FormatPicker.pickVideo(parsed.formats, preferredItag)
        } ?: throw ExtractionException.NoFormats(
            "no allowed ${kind.label} itag on ${client.id}"
        )

        val finalUrl = materializeUrl(client, playerResponse, format)

        // GVS token decorates only the googlevideo URL of clients that
        // require it (F3) — never mobile clients' unsigned URLs.
        val url = if (client.requiresGvsPoToken) {
            val gvsToken = poToken?.token(PoTokenBinding.GVS, videoId)
                ?: throw ExtractionException.PoTokenRequired(client.id)
            "$finalUrl&pot=$gvsToken"
        } else {
            // Content-playback nonce matching the request — like the real app.
            "$finalUrl&cpn=$cpn"
        }

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

    /** Visitor tokens fetched per mobile client for the session. */
    private val visitorCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Real `visitorData` for [client] — null in tests (endpoint override) or
     * when the fetch fails (the field is then simply omitted, like a fresh
     * app install that has not talked to YouTube yet).
     */
    private suspend fun visitorDataFor(
        client: YouTubeClientCatalog.Client,
    ): String? {
        if (playerEndpoint != null) return InnerTubeHeaders.visitorData
        if (!client.mobile) return InnerTubeHeaders.visitorData
        visitorCache[client.id]?.let { return it }
        val fetched = runCatching { fetchVisitorId(client) }.getOrNull()
        if (fetched != null) visitorCache[client.id] = fetched
        return fetched
    }

    /**
     * `POST /youtubei/v1/visitor_id` with the client's context — the real
     * apps' way of obtaining a bound visitor token.
     */
    private fun fetchVisitorId(client: YouTubeClientCatalog.Client): String? {
        val body = PlayerRequestBuilder.buildContextOnly(client)
        val req = Request.Builder()
            .url("$VISITOR_ENDPOINT?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply {
                client.userAgent?.let { header("User-Agent", it) }
                if (client.mobile) header("X-Goog-Api-Format-Version", "2")
            }
            .build()
        return runCatching {
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = resp.body?.string()
                    ?.let { json.parseToJsonElement(it) as? JsonObject }
                    ?: return@use null
                (obj["responseContext"] as? JsonObject)
                    ?.get("visitorData")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.contentOrNull
            }
        }.getOrNull()
    }

    private fun endpointFor(client: YouTubeClientCatalog.Client): String =
        playerEndpoint
            ?: if (client.mobile) MOBILE_PLAYER_ENDPOINT else WEB_PLAYER_ENDPOINT

    private fun postPlayer(
        client: YouTubeClientCatalog.Client,
        body: JsonObject,
    ): JsonObject {
        val req = Request.Builder()
            .url("${endpointFor(client)}?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply {
                client.userAgent?.let { header("User-Agent", it) }
                if (client.mobile) {
                    header("X-Goog-Api-Format-Version", "2")
                } else {
                    InnerTubeHeaders.applyTo(this)
                }
            }
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
        const val TAG = "StreamResolver"

        // android.util.Log throws "not mocked" under plain JVM unit tests —
        // route through helpers that tolerate the stub runtime.
        fun logi(tag: String, msg: String) {
            runCatching { android.util.Log.i(tag, msg) }
        }
        fun logw(tag: String, msg: String) {
            runCatching { android.util.Log.w(tag, msg) }
        }
        fun loge(tag: String, msg: String) {
            runCatching { android.util.Log.e(tag, msg) }
        }
        const val MOBILE_PLAYER_ENDPOINT =
            "https://youtubei.googleapis.com/youtubei/v1/player"
        const val WEB_PLAYER_ENDPOINT =
            "https://music.youtube.com/youtubei/v1/player"
        const val VISITOR_ENDPOINT =
            "https://youtubei.googleapis.com/youtubei/v1/visitor_id"
        val JSON = "application/json; charset=utf-8".toMediaType()

        private val NONCE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val NONCE_RANDOM = java.security.SecureRandom()

        /** Alphanumeric nonce like the real apps' `cpn`/`t` params. */
        fun randomNonce(length: Int): String =
            (1..length)
                .map { NONCE_CHARS[NONCE_RANDOM.nextInt(NONCE_CHARS.length)] }
                .joinToString("")
    }
}

private object PianoStreamId {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    fun isVideoId(id: String) = VIDEO_ID.matches(id)
}
