package com.pianostream.core.media.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadRepository
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DownloadManager on a local MockWebServer: resolves via the fake
 * [StreamResolver], streams bytes to filesDir/downloads/<id>.bin through a
 * .part file + atomic rename, and records every state transition in the
 * repository. Cancellation deletes the .part remnant; a killed-process row
 * (still DOWNLOADING on disk) recovers to FAILED on the next start.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadManagerTest {

    private val videoId = "abc123def45"
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var repo: FakeDownloadRepository
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        File(ctx.filesDir, "downloads").deleteRecursively()
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = FakeDownloadRepository()
    }

    @After
    fun teardown() {
        File(ctx.filesDir, "downloads").deleteRecursively()
        server.shutdown()
    }

    private fun resolverFor(body: ByteArray) = object : StreamResolver {
        override suspend fun resolve(
            videoId: String,
            preferredItag: Int,
            force: Boolean,
        ): ResolvedStream = ResolvedStream(
            videoId = videoId,
            url = server.url("/audio/$videoId").toString(),
            itag = 251,
            mimeType = "audio/webm",
            bitrate = 130_000,
            contentLength = body.size.toLong(),
            expiresAtEpochMs = Long.MAX_VALUE,
            expiresInSeconds = 3600,
            headers = emptyMap(),
            clientId = "test",
            resolvedAtUptimeMs = 0,
            expiresAtUptimeMs = Long.MAX_VALUE,
        )
    }

    private fun manager(resolver: StreamResolver) = DownloadManager(
        context = ctx,
        resolver = resolver,
        okHttp = OkHttpClient(),
        repository = repo,
        scope = scope,
    )

    private fun body(bytes: Int) = Random.nextBytes(bytes)

    @Test
    fun enqueue_writes_file_and_reaches_done() = runTest {
        val bytes = body(300_000)
        server.enqueue(
            MockResponse().setBody(Buffer().write(bytes))
                .setHeader("Content-Length", bytes.size.toString()),
        )
        val dm = manager(resolverFor(bytes))

        dm.enqueue(videoId, "Song", "Artist", "https://art/x")
        dm.awaitIdle()

        val item = repo.get(videoId)!!
        assertThat(item.state).isEqualTo(DownloadState.DONE)
        assertThat(item.bytesDownloaded).isEqualTo(bytes.size.toLong())
        assertThat(item.bytesTotal).isEqualTo(bytes.size.toLong())
        val file = File(ctx.filesDir, "downloads/$videoId.bin")
        assertThat(file.isFile).isTrue()
        assertThat(file.readBytes()).isEqualTo(bytes)
        assertThat(File(ctx.filesDir, "downloads/$videoId.bin.part").exists()).isFalse()
        assertThat(dm.fileFor(videoId)).isEqualTo(file)
    }

    @Test
    fun remove_deletes_file_and_row() = runTest {
        val bytes = body(4_096)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val dm = manager(resolverFor(bytes))
        dm.enqueue(videoId, "Song")
        dm.awaitIdle()
        // fileFor reads the StateFlow mirror of the store — collected on
        // Dispatchers.IO, so the DONE row lands a beat after the upsert.
        assertThat(waitFor { dm.fileFor(videoId) != null }).isTrue()

        dm.remove(videoId)
        assertThat(dm.fileFor(videoId)).isNull()
        assertThat(repo.get(videoId)).isNull()
        assertThat(File(ctx.filesDir, "downloads/$videoId.bin").exists()).isFalse()
    }

    @Test
    fun enqueue_same_id_twice_is_noop() = runTest {
        val bytes = body(4_096)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val dm = manager(resolverFor(bytes))
        dm.enqueue(videoId, "Song")
        dm.enqueue(videoId, "Song again")
        dm.awaitIdle()
        // Only ONE request hit the server.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun resolver_failure_marks_failed() = runTest {
        val dm = manager(
            object : StreamResolver {
                override suspend fun resolve(
                    videoId: String,
                    preferredItag: Int,
                    force: Boolean,
                ): ResolvedStream = throw IllegalStateException("offline")
            },
        )
        dm.enqueue(videoId, "Song")
        dm.awaitIdle()
        val item = repo.get(videoId)!!
        assertThat(item.state).isEqualTo(DownloadState.FAILED)
        assertThat(item.error).contains("offline")
        assertThat(dm.fileFor(videoId)).isNull()
    }

    @Test
    fun http_error_marks_failed() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val dm = manager(resolverFor(body(1)))
        dm.enqueue(videoId, "Song")
        dm.awaitIdle()
        assertThat(repo.get(videoId)!!.state).isEqualTo(DownloadState.FAILED)
        assertThat(File(ctx.filesDir, "downloads/$videoId.bin").exists()).isFalse()
    }

    @Test
    fun remove_midflight_cancels_and_cleans_part() = runTest {
        // A body that never finishes: throttle to a trickle so we can cancel mid-read.
        val bytes = body(600_000)
        server.enqueue(
            MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024, 500, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val dm = manager(resolverFor(bytes))
        dm.enqueue(videoId, "Song")
        // Let the download start.
        val deadline = System.currentTimeMillis() + 5_000
        while (repo.get(videoId)?.state != DownloadState.DOWNLOADING &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(10)
        }
        assertThat(repo.get(videoId)?.state).isEqualTo(DownloadState.DOWNLOADING)

        dm.remove(videoId)
        dm.awaitIdle()
        assertThat(repo.get(videoId)).isNull()
        assertThat(File(ctx.filesDir, "downloads/$videoId.bin").exists()).isFalse()
        assertThat(File(ctx.filesDir, "downloads/$videoId.bin.part").exists()).isFalse()
    }

    @Test
    fun interrupted_row_recovers_to_failed_on_next_boot() = runTest {
        // Simulate a process kill: row still DOWNLOADING in the store.
        repo.upsert(
            DownloadItem(videoId = videoId, title = "Song", state = DownloadState.DOWNLOADING),
        )
        val dm = manager(resolverFor(body(1)))
        dm.awaitIdle() // lets recoverInterrupted's coroutine run
        val deadline = System.currentTimeMillis() + 5_000
        var item = repo.get(videoId)
        while (item?.state != DownloadState.FAILED && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(10)
            item = repo.get(videoId)
        }
        assertThat(item!!.state).isEqualTo(DownloadState.FAILED)
    }

    @Test
    fun fileFor_ignores_non_done_or_missing_file() = runTest {
        val dm = manager(resolverFor(body(1)))
        assertThat(dm.fileFor(videoId)).isNull()

        repo.upsert(
            DownloadItem(videoId = videoId, title = "Song", state = DownloadState.DONE),
        )
        // DONE row but no file on disk → still null (deleted out from under it).
        assertThat(dm.fileFor(videoId)).isNull()
    }

    private suspend fun waitFor(block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (block()) return true
            kotlinx.coroutines.delay(10)
        }
        return block()
    }

    /** In-memory repo — the Room store itself is covered by core/data tests. */
    private class FakeDownloadRepository : DownloadRepository {
        private val rows = MutableStateFlow<List<DownloadItem>>(emptyList())
        override fun observeAll(): Flow<List<DownloadItem>> = rows
        override fun observe(videoId: String): Flow<DownloadItem?> =
            rows.map { list -> list.firstOrNull { it.videoId == videoId } }
        override suspend fun get(videoId: String): DownloadItem? =
            rows.value.firstOrNull { it.videoId == videoId }
        override suspend fun upsert(item: DownloadItem) {
            rows.value = rows.value.filterNot { it.videoId == item.videoId } + item
        }
        override suspend fun delete(videoId: String) {
            rows.value = rows.value.filterNot { it.videoId == videoId }
        }
        override suspend fun clear() { rows.value = emptyList() }
        override suspend fun failActive(error: String) {
            rows.value = rows.value.map {
                if (it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING) {
                    it.copy(state = DownloadState.FAILED, error = error)
                } else it
            }
        }
    }
}
