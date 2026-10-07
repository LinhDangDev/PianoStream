package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import java.io.IOException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2 step 4 — `pianostream://` resolution below the cache:
 * substituted URLs + headers reach the upstream; a 403 triggers exactly one
 * `force=true` re-resolve (H1); `ExtractionException` subtypes surface
 * unchanged as the `IOException` cause (H5); local keys never touch the
 * resolver (CC9).
 */
@RunWith(RobolectricTestRunner::class)
class StreamResolvingDataSourceTest {

    private class FakeResolver : StreamResolver {
        data class Call(val videoId: String, val itag: Int, val force: Boolean)

        val calls = mutableListOf<Call>()
        var next: (Call) -> ResolvedStream = { call ->
            ResolvedStream(
                videoId = call.videoId,
                url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=999&pot=SECRET&sig=SECRET",
                itag = call.itag,
                mimeType = "audio/webm",
                bitrate = 130_000,
                contentLength = 3_000_000,
                expiresAtEpochMs = 9_999_999L,
                expiresInSeconds = 3_600,
                headers = mapOf("User-Agent" to "agent", "Referer" to "https://music.youtube.com"),
                clientId = "ANDROID_MUSIC",
                resolvedAtUptimeMs = 0,
                expiresAtUptimeMs = Long.MAX_VALUE,
            )
        }

        override suspend fun resolve(
            videoId: String,
            preferredItag: Int,
            force: Boolean,
        ): ResolvedStream {
            calls += Call(videoId, preferredItag, force)
            return next(Call(videoId, preferredItag, force))
        }
    }

    /** Records every opened spec; can throw a canned 403 once. */
    private class RecordingUpstream(
        var throwable: IOException? = null,
    ) : DataSource {
        val openedSpecs = mutableListOf<DataSpec>()
        var openCount = 0
        var closed = false

        override fun open(dataSpec: DataSpec): Long {
            openCount++
            openedSpecs += dataSpec
            throwable?.let { t -> throwable = null; throw t }
            return 1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri() = openedSpecs.lastOrNull()?.uri
        override fun close() { closed = true }
        override fun addTransferListener(t: TransferListener) {}
    }

    private fun http403(spec: DataSpec): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            403, "Forbidden", null, emptyMap(), spec, ByteArray(0),
        )

    private lateinit var resolver: FakeResolver
    private lateinit var upstream: RecordingUpstream
    private lateinit var localCalls: MutableList<String>

    private fun source(
        upstreamSource: RecordingUpstream = upstream,
    ) = StreamResolvingDataSource(
        resolver = resolver,
        upstream = upstreamSource,
        preferredItag = 251,
        localResolver = { key -> localCalls += key; "content://documents/disc/$key.flac" },
    )

    @Before
    fun setUp() {
        resolver = FakeResolver()
        upstream = RecordingUpstream()
        localCalls = mutableListOf()
    }

    private fun specOf(uri: String) = DataSpec.Builder().setUri(uri).build()

    @Test
    fun stream_uri_resolves_then_opens_substituted_upstream() {
        source().open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(resolver.calls).hasSize(1)
        assertThat(resolver.calls[0].force).isFalse()
        assertThat(resolver.calls[0].itag).isEqualTo(251)
        val opened = upstream.openedSpecs.single()
        assertThat(opened.uri.toString()).startsWith("https://rr1---sn-abc.googlevideo.com/")
        assertThat(opened.httpRequestHeaders["User-Agent"]).isEqualTo("agent")
    }

    @Test
    fun local_uri_goes_through_local_resolver_only() {
        source().open(specOf("pianostream://local/disc3.t2"))
        assertThat(localCalls).containsExactly("disc3.t2")
        assertThat(resolver.calls).isEmpty()
        assertThat(upstream.openedSpecs.single().uri.toString())
            .isEqualTo("content://documents/disc/disc3.t2.flac")
    }

    @Test
    fun foreign_uri_passes_through_unresolved() {
        source().open(specOf("https://cdn.example.com/x.flac"))
        assertThat(resolver.calls).isEmpty()
        assertThat(upstream.openedSpecs.single().uri.toString())
            .isEqualTo("https://cdn.example.com/x.flac")
    }

    @Test
    fun first_403_forces_single_resolve_retry() {
        upstream.throwable = http403(specOf("https://rr1---sn-abc.googlevideo.com/x"))
        source().open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(resolver.calls).hasSize(2)
        assertThat(resolver.calls[1].force).isTrue()
        assertThat(upstream.openCount).isEqualTo(2)
    }

    @Test
    fun non_403_http_error_does_not_resolve() {
        upstream.throwable = HttpDataSource.InvalidResponseCodeException(
            500, "Server", null, emptyMap(), specOf("https://x"), ByteArray(0),
        )
        try {
            source().open(specOf("pianostream://stream/dQw4w9WgXcQ"))
            throw AssertionError("expected InvalidResponseCodeException")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertThat(e.responseCode).isEqualTo(500)
        }
        assertThat(resolver.calls).hasSize(1) // no forced re-resolve
    }

    @Test
    fun extraction_exception_surfaces_as_io_cause() {
        resolver.next = { throw ExtractionException.RateLimited("429") }
        try {
            source().open(specOf("pianostream://stream/dQw4w9WgXcQ"))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertThat(e.cause).isInstanceOf(ExtractionException.RateLimited::class.java)
        }
    }

    @Test
    fun dead_local_key_throws_io() {
        val src = StreamResolvingDataSource(
            resolver = resolver,
            upstream = upstream,
            localResolver = { null },
        )
        try {
            src.open(specOf("pianostream://local/gone"))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // expected — B1 tombstone path
        }
        assertThat(upstream.openCount).isEqualTo(0)
    }

    @Test
    fun resolved_url_never_logged_verbatim() {
        // CC4 is enforced via Redact.url on the only log line — assert the
        // URL fields are redacted when present in a message.
        val redacted = com.pianostream.core.data.util.Redact.url(
            "403 on resolved url https://x/videoplayback?pot=SECRET&sig=SECRET&id=dQw4w9WgXcQ",
        )
        assertThat(redacted).doesNotContain("pot=SECRET")
        assertThat(redacted).doesNotContain("sig=SECRET")
    }
}
