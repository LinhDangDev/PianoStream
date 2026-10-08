package com.pianostream.core.media.datasource

import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.data.util.Redact
import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.api.StreamResolver
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * `pianostream://` URI → live URL resolution (Phase 2 step 4). Sits below the
 * `SimpleCache` in the data-source stack so the cache sees stable mediaIds:
 *
 *  - `pianostream://stream/<videoId>` → [StreamResolver.resolve] → fresh
 *    `googlevideo` URL + headers, substituted into the spec before opening
 *    the real upstream.
 *  - `pianostream://video/<videoId>` → [StreamResolver.resolveVideo] → fresh
 *    adaptive video-only URL (the itag comes from [videoItagProvider] —
 *    `null` = auto) — identical substitution, 403-retry and redaction rules.
 *  - `pianostream://local/<contentKey>` → the [localResolver] maps the key to
 *    its SAF `content://` FLAC document URI (resolved through `discs`/`tracks`
 *    — the synthetic URI's path is never trusted, CC9).
 *  - anything else passes through untouched (already-materialized URLs).
 *
 * H1: on an upstream `InvalidResponseCodeException` with `responseCode == 403`
 * the cached stream entry is evicted, the client records a failure and a
 * single `force = true` re-resolve retries once. `RateLimited` is never folded
 * into this path. Every failure surfaces as an `IOException` whose `cause` is
 * the [ExtractionException] subtype unchanged (H5 — Phase 4 maps it).
 *
 * CC4: `DataSpec.uri` is never logged verbatim — every logged URL passes
 * through [Redact.url]; header values are never logged.
 */
class StreamResolvingDataSource(
    private val resolver: StreamResolver,
    private val upstream: DataSource,
    private val preferredItag: Int = 251,
    /** contentKey → `content://` document URI; the resolver below the cache
     *  never sees local paths. Returns null when the key is dead (B1). */
    private val localResolver: suspend (contentKey: String) -> String?,
    /** Video-quality tier itag for `video/` resolves; `null` = auto. Read
     *  per open so a mid-playback quality switch applies on the next source. */
    private val videoItagProvider: () -> Int? = { null },
) : DataSource {

    private var openedUpstreamUri: android.net.Uri? = null

    /** Factory so the stack can create per-source instances. */
    class Factory(
        private val resolver: StreamResolver,
        private val upstreamFactory: () -> DataSource,
        private val preferredItag: Int = 251,
        private val localResolver: suspend (String) -> String?,
        private val videoItagProvider: () -> Int? = { null },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            StreamResolvingDataSource(
                resolver,
                upstreamFactory(),
                preferredItag,
                localResolver,
                videoItagProvider,
            )
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri?.toString() ?: throw IOException("null uri")
        return when (val id = PianoStreamUri.parse(uri)) {
            is PianoStreamUri.Id.Stream -> openResolved(dataSpec, id.videoId, video = false)
            is PianoStreamUri.Id.Video -> openResolved(dataSpec, id.videoId, video = true)
            is PianoStreamUri.Id.Local -> openLocal(dataSpec, id.contentKey)
            null -> openUpstream(dataSpec) // non-pianostream (e.g. content://)
        }
    }

    private fun openResolved(dataSpec: DataSpec, videoId: String, video: Boolean): Long {
        val resolved = resolveOrThrow(videoId, video, force = false)
        val spec = substitute(dataSpec, resolved.url, resolved.headers)
        try {
            return upstream.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403) throw e
            // H1: first 403 — evict + recordFailure + one forced re-resolve.
            closeQuietly()
            logd("403 on resolved url — forcing re-resolve for $videoId")
            val fresh = resolveOrThrow(videoId, video, force = true)
            val retrySpec = substitute(dataSpec, fresh.url, fresh.headers)
            return upstream.open(retrySpec)
        }
    }

    private fun openLocal(dataSpec: DataSpec, contentKey: String): Long {
        val docUri = runBlocking { localResolver(contentKey) }
            ?: throw IOException(ExtractionException.NoFormats("dead local key $contentKey"))
        return upstream.open(dataSpec.buildUpon().setUri(docUri).build())
    }

    private fun openUpstream(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    private fun resolveOrThrow(videoId: String, video: Boolean, force: Boolean) = try {
        runBlocking {
            if (video) {
                resolver.resolveVideo(
                    videoId,
                    videoItagProvider() ?: StreamResolver.VIDEO_ITAG_AUTO,
                    force,
                )
            } else {
                resolver.resolve(videoId, preferredItag, force)
            }
        }
    } catch (e: ExtractionException) {
        // H5 — surface the subtype unchanged as the cause.
        throw IOException("${if (video) "video" else "stream"} resolve failed for $videoId", e)
    }

    private fun substitute(
        spec: DataSpec,
        url: String,
        headers: Map<String, String>,
    ): DataSpec = spec.buildUpon()
        .setUri(url)
        .apply {
            if (headers.isNotEmpty()) setHttpRequestHeaders(headers)
        }
        .build()

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): android.net.Uri? = upstream.uri ?: openedUpstreamUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        openedUpstreamUri = null
        upstream.close()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    private fun closeQuietly() = runCatching { upstream.close() }

    private fun logd(msg: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, Redact.url(msg))
    }

    companion object {
        private const val TAG = "StreamResolving"
    }
}
