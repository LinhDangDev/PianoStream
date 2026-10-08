package com.pianostream.core.youtube.player

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.cache.LruStreamUrlCache
import com.pianostream.core.youtube.cipher.CipherSolver
import com.pianostream.core.youtube.cipher.PlayerConfig
import com.pianostream.core.youtube.cipher.PlayerJsFetcher
import com.pianostream.core.youtube.cipher.RemotePlayerConfigStore
import com.pianostream.core.youtube.cipher.SolveRequest
import com.pianostream.core.youtube.cipher.SolvedCipher
import com.pianostream.core.youtube.client.ClientFallbackStrategy
import com.pianostream.core.youtube.client.ClientHealthTracker
import com.pianostream.core.youtube.client.YouTubeClientCatalog
import com.pianostream.core.youtube.potoken.PoTokenBinding
import com.pianostream.core.youtube.potoken.PoTokenProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolver integration over MockWebServer: itag picking, expire clamp, cache,
 * H1 force-resolve, RateLimited, videoId-mismatch demotion, poToken binding
 * separation — every failure is an [ExtractionException].
 */
class StreamResolverImplTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File

    private var uptime = 0L
    private var epoch = 1_700_000_000_000L

    private val health = ClientHealthTracker(uptimeMs = { uptime })
    private val cache = LruStreamUrlCache(uptimeMs = { uptime })

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("player/$name")!!.readBytes()
            .decodeToString()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        filesDir = java.nio.file.Files.createTempDirectory("pstest").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        filesDir.deleteRecursively()
    }

    private val neverSolve = object : CipherSolver {
        override suspend fun solve(request: SolveRequest): SolvedCipher =
            throw ExtractionException.CipherFailure("not expected in this test")
    }

    private fun resolver(
        catalog: List<YouTubeClientCatalog.Client>? = null,
        poToken: PoTokenProvider? = null,
        configStore: RemotePlayerConfigStore = RemotePlayerConfigStore(
            seedJson = """{"configs":{"default":{"sig":"s","nClass":"n"}}}"""
                .toByteArray(),
            filesDir = filesDir,
            enabledProvider = { false },
        ),
    ) = StreamResolverImpl(
        health = health,
        fallback = ClientFallbackStrategy(
            health,
            catalog ?: YouTubeClientCatalog.ordered.filter {
                !it.requiresAuth && !it.requiresGvsPoToken
            },
        ),
        cipherSolver = neverSolve,
        poToken = poToken,
        cache = cache,
        configStore = configStore,
        playerJs = PlayerJsFetcher(),
        okHttp = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        epochMs = { epoch },
        uptimeMs = { uptime },
        playerEndpoint = server.url("/youtubei/v1/player").toString(),
    )

    @Test
    fun `resolve returns itag 251 webm opus with clamped expiry`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        val s = resolver().resolve("dQw4w9WgXcQ")

        assertThat(s.itag).isEqualTo(251)
        assertThat(s.mimeType).contains("webm")
        assertThat(s.mimeType).contains("opus")
        assertThat(s.url).startsWith("https://rr1---sn-example.googlevideo.com/")
        // expire=2000000000 is >6h out → clamped to now+6h.
        assertThat(s.expiresAtEpochMs).isEqualTo(epoch + PlayerResponseParser.MAX_CLAMP_MS)
        assertThat(s.isFresh(uptime)).isTrue()
        assertThat(s.toString()).doesNotContain("expire=")
    }

    @Test
    fun `falls back to itag 140 audio mp4 when 251 absent`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("player_response_140only.json")))
        val s = resolver().resolve("dQw4w9WgXcQ")
        assertThat(s.itag).isEqualTo(140)
        assertThat(s.mimeType).contains("audio/mp4")
    }

    @Test
    fun `cached hit skips the network`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        val r = resolver()
        r.resolve("dQw4w9WgXcQ")
        r.resolve("dQw4w9WgXcQ") // second call — cache hit, no second request
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `force resolve evicts cache and excludes the cached client`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        val r = resolver()
        val first = r.resolve("dQw4w9WgXcQ")
        val firstClient = first.clientId
        val second = r.resolve("dQw4w9WgXcQ", force = true)
        assertThat(second.clientId).isNotEqualTo(firstClient)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(health.consecutiveFailures(firstClient)).isEqualTo(1)
    }

    @Test
    fun `429 maps to RateLimited immediately`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val err = runCatching { resolver().resolve("dQw4w9WgXcQ") }.exceptionOrNull()
        assertThat(err).isInstanceOf(ExtractionException.RateLimited::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `videoId mismatch marks client unhealthy and moves on`() = runTest {
        val mismatched = fixture("player_response_visionos.json")
            .replace("dQw4w9WgXcQ", "AAAAAAAAAAA")
        server.enqueue(MockResponse().setBody(mismatched))
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        val s = resolver().resolve("dQw4w9WgXcQ")
        assertThat(s.itag).isEqualTo(251)
        // First client recorded a failure; second client served the stream.
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(
            health.consecutiveFailures(YouTubeClientCatalog.ordered[0].id)
        ).isEqualTo(1)
    }

    @Test
    fun `every client failing throws ExtractionException never a stale URL`() = runTest {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(403)) }
        val err = runCatching { resolver().resolve("dQw4w9WgXcQ") }.exceptionOrNull()
        assertThat(err).isInstanceOf(ExtractionException::class.java)
    }

    @Test
    fun `invalid videoId rejected before any request`() = runTest {
        val err = runCatching { resolver().resolve("AAAA/itag") }.exceptionOrNull()
        assertThat(err).isNotNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `GVS token decorates only the googlevideo URL`() = runTest {
        val gvsClient = YouTubeClientCatalog.Client(
            id = "TEST_GVS",
            name = "WEB_REMIX",
            version = "1.0",
            lifecycle = YouTubeClientCatalog.Lifecycle.STABLE,
            requiresAuth = false,
            requiresGvsPoToken = true,
        )
        val minted = mutableListOf<Pair<PoTokenBinding, String>>()
        val poToken = object : PoTokenProvider {
            override suspend fun token(binding: PoTokenBinding, key: String): String? {
                minted += binding to key
                return when (binding) {
                    PoTokenBinding.VISITOR -> "visitorTok_1"
                    PoTokenBinding.GVS -> "gvsTok_$key"
                }
            }
            override fun cached(binding: PoTokenBinding, key: String) = null
            override fun wipe() {}
        }
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        val s = resolver(
            catalog = listOf(gvsClient),
            poToken = poToken,
        ).resolve("dQw4w9WgXcQ")

        // Final URL carries the GVS token as pot=.
        assertThat(s.url).contains("pot=gvsTok_dQw4w9WgXcQ")
        // Player request carried the VISITOR token — never the GVS one (F3).
        val sent = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
        assertThat(sent).contains("visitorTok_1")
        assertThat(sent).doesNotContain("gvsTok")
        assertThat(minted.map { it.first }).containsExactly(
            PoTokenBinding.VISITOR, PoTokenBinding.GVS,
        ).inOrder()
    }

    @Test
    fun `missing formats map to NoFormats and try the next client`() = runTest {
        val noFormats = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"dQw4w9WgXcQ"},"streamingData":{"adaptiveFormats":[]}}"""
        server.enqueue(MockResponse().setBody(noFormats))
        server.enqueue(MockResponse().setBody(fixture("player_response_visionos.json")))
        val s = resolver().resolve("dQw4w9WgXcQ")
        assertThat(s.itag).isEqualTo(251)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `resolveVideo auto returns the best adaptive itag 137 mp4`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("player_response_muxed.json")))
        val s = resolver().resolveVideo("dQw4w9WgXcQ")
        assertThat(s.itag).isEqualTo(137)
        assertThat(s.mimeType).contains("video/mp4")
        assertThat(s.url).startsWith("https://rr1---sn-example.googlevideo.com/")
        assertThat(s.toString()).doesNotContain("expire=")
    }

    @Test
    fun `resolveVideo picks the next lower adaptive when the top rung is absent`() = runTest {
        val only136 = fixture("player_response_muxed.json")
            .replace("\"itag\": 137", "\"itag\": 136")
        server.enqueue(MockResponse().setBody(only136))
        val s = resolver().resolveVideo("dQw4w9WgXcQ")
        assertThat(s.itag).isEqualTo(136)
    }

    @Test
    fun `resolveVideo honors preferredItag at-or-below the tier`() = runTest {
        // Only 480p sits at-or-below a 720p request (137/251 are unusable).
        val only480 = fixture("player_response_muxed.json")
            .replace("\"itag\": 137", "\"itag\": 135")
        server.enqueue(MockResponse().setBody(only480))
        val s = resolver().resolveVideo("dQw4w9WgXcQ", preferredItag = 136)
        assertThat(s.itag).isEqualTo(135)
    }

    @Test
    fun `resolveVideo floors at the lowest adaptive above a low request`() = runTest {
        // 144p request on a video whose lowest adaptive rung is 1080p → 137.
        server.enqueue(MockResponse().setBody(fixture("player_response_muxed.json")))
        val s = resolver().resolveVideo("dQw4w9WgXcQ", preferredItag = 160)
        assertThat(s.itag).isEqualTo(137)
    }

    @Test
    fun `resolveVideo rejects a preferred itag outside the video allow-list`() = runTest {
        val err = runCatching {
            resolver().resolveVideo("dQw4w9WgXcQ", preferredItag = 22)
        }.exceptionOrNull()
        assertThat(err).isInstanceOf(ExtractionException.NoFormats::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `resolveVideo throws NoFormats when only muxed formats exist`() = runTest {
        // Muxed progressive 22/18 are never picked — the merged video child
        // must be video-only; a response without adaptive video fails through
        // the same NoFormats path on every client.
        val muxedOnly = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"dQw4w9WgXcQ"},"streamingData":{"formats":[{"itag":22,"url":"https://rr1---sn-example.googlevideo.com/videoplayback?expire=2000000000&id=o-abc&itag=22","mimeType":"video/mp4"},{"itag":18,"url":"https://rr1---sn-example.googlevideo.com/videoplayback?expire=2000000000&id=o-abc&itag=18","mimeType":"video/mp4"}],"adaptiveFormats":[{"itag":251,"url":"https://rr1---sn-example.googlevideo.com/videoplayback?expire=2000000000&id=o-abc&itag=251","mimeType":"audio/webm"}]}}"""
        repeat(6) { server.enqueue(MockResponse().setBody(muxedOnly)) }
        val err = runCatching { resolver().resolveVideo("dQw4w9WgXcQ") }.exceptionOrNull()
        assertThat(err).isInstanceOf(ExtractionException.NoFormats::class.java)
    }

    @Test
    fun `resolveVideo caches separately from audio resolve`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("player_response_muxed.json")))
        server.enqueue(MockResponse().setBody(fixture("player_response_muxed.json")))
        val r = resolver()
        val video = r.resolveVideo("dQw4w9WgXcQ")
        val audio = r.resolve("dQw4w9WgXcQ") // audio needs its own fetch
        assertThat(video.itag).isEqualTo(137)
        assertThat(audio.itag).isEqualTo(251)
        assertThat(server.requestCount).isEqualTo(2)
        // Second video resolve hits the video cache entry.
        r.resolveVideo("dQw4w9WgXcQ")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `default resolveVideo on audio-only resolver throws NoFormats`() = runTest {
        val fake = object : com.pianostream.core.youtube.api.StreamResolver {
            override suspend fun resolve(
                videoId: String,
                preferredItag: Int,
                force: Boolean,
            ) = throw ExtractionException.AllClientsFailed("unused")
        }
        val err = runCatching { fake.resolveVideo("dQw4w9WgXcQ") }.exceptionOrNull()
        assertThat(err).isInstanceOf(ExtractionException.NoFormats::class.java)
    }
}
