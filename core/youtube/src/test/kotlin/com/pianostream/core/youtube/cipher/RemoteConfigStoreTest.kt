package com.pianostream.core.youtube.cipher

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * F15 remote-config gate + single-flight + ETag/TTL cache semantics.
 * MockWebServer counts requests — the gate-off case must emit ZERO.
 */
class RemoteConfigStoreTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File

    private val seed = """{"version":1,"configs":{"seedhash":{"sig":"s","nClass":"n"}}}"""
        .toByteArray()
    private val registryJson =
        """{"version":1,"configs":{"h1":{"sig":"s1","nClass":"n1"}}}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        filesDir = java.nio.file.Files.createTempDirectory("pianostream-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        filesDir.deleteRecursively()
    }

    private fun store(enabled: Boolean) = RemotePlayerConfigStore(
        seedJson = seed,
        filesDir = filesDir,
        enabledProvider = { enabled },
        registryUrls = listOf(server.url("/registry.json").toString()),
        allowCheck = { true }, // MockWebServer is http://localhost — allow-listed
    )

    @Test
    fun `gate off emits zero requests and serves seed`() = runTest {
        val s = store(enabled = false)
        server.enqueue(MockResponse().setBody(registryJson))

        assertThat(s.configFor("seedhash")).isNotNull()
        // Unknown hash with gate closed → fail closed, still zero requests.
        assertThat(s.configFor("unknown")).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `gate off force refresh still emits zero requests`() = runTest {
        val s = store(enabled = false)
        s.refresh(force = true)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `gate on unknown hash triggers exactly one refresh`() = runTest {
        val s = store(enabled = true)
        server.enqueue(MockResponse().setBody(registryJson))
        assertThat(s.configFor("h1")!!.sig).isEqualTo("s1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `304 keeps the cached config`() = runTest {
        val s = store(enabled = true)
        server.enqueue(
            MockResponse().setBody(registryJson).addHeader("ETag", "v1")
        )
        server.enqueue(MockResponse().setResponseCode(304))
        s.configFor("h1")
        s.refresh(force = true)
        assertThat(s.configFor("h1")!!.sig).isEqualTo("s1")
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!! // first (unconditional) fetch
        val second = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertThat(second.getHeader("If-None-Match")).isEqualTo("v1")
    }

    @Test
    fun `two concurrent resolves cause one request`() = runTest {
        val s = store(enabled = true)
        server.enqueue(
            MockResponse().setBody(registryJson)
                .setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        val a = async { s.configFor("h1", forceRefresh = true) }
        val b = async { s.configFor("h1", forceRefresh = true) }
        awaitAll(a, b)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `tampered payload is rejected and never cached`() = runTest {
        val s = store(enabled = true)
        server.enqueue(MockResponse().setBody("""{"configs":{"h":{"sig":"x;y","nClass":"n"}}}"""))
        assertThat(s.configFor("h")).isNull()
        assertThat(File(filesDir, "player_configs.json").exists()).isFalse()
    }

    @Test
    fun `registry from non-allow-listed host is refused`() = runTest {
        val s = RemotePlayerConfigStore(
            seedJson = seed,
            filesDir = filesDir,
            enabledProvider = { true },
            registryUrls = listOf("https://evil.example.com/x.json"),
        )
        s.refresh(force = true)
        assertThat(s.activeHost).isNull()
        // and the real allow-list check refuses non-https/non-listed hosts
        val bad = listOf(
            "http://raw.githubusercontent.com/x", // not https
            "https://evil.example.com/x",
            "notaurl",
        )
        bad.forEach {
            assertThat(com.pianostream.core.youtube.client.RegistryHosts.isAllowed(it))
                .isFalse()
        }
    }

    @Test
    fun `wipe drops persisted config`() = runTest {
        val s = store(enabled = true)
        server.enqueue(MockResponse().setBody(registryJson))
        s.configFor("h1")
        assertThat(File(filesDir, "player_configs.json").exists()).isTrue()
        s.wipe()
        assertThat(File(filesDir, "player_configs.json").exists()).isFalse()
    }
}
