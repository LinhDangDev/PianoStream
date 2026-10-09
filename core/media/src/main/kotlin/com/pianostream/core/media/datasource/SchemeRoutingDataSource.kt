package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.pianostream.core.data.uri.PianoStreamUri
import java.io.IOException

/**
 * Routes `pianostream://local/…` opens to the plain (uncached) resolving
 * source and everything else to the `CacheDataSource`. Local FLAC bytes are
 * never streamed through `SimpleCache` (a 21 GB library would obliterate the
 * 512 MiB streaming budget); `ContentIdCacheKeyFactory` returning `null` for
 * `local/` stays a dead-code contract assertion instead of a live NPE.
 * `pianostream://video/…` takes the cached branch like `stream/` — muxed
 * mp4 bytes cache under their own mediaId.
 *
 * `pianostream://stream/<videoId>` opens whose videoId reports a finished
 * download through [downloadedProvider] also take the uncached branch:
 * their bytes come from `filesDir/downloads/` (local disk), so caching
 * them into `SimpleCache` would just duplicate the file a second time.
 */
class SchemeRoutingDataSource(
    private val cached: DataSource,
    private val uncached: DataSource,
    /** videoId → a finished offline download exists for it. Read per open;
     *  cheap and non-blocking (state-map lookup). */
    private val downloadedProvider: (videoId: String) -> Boolean = { false },
) : DataSource {

    private var active: DataSource? = null

    private fun delegateFor(uri: android.net.Uri?): DataSource {
        val uriString = uri?.toString()
        if (uriString?.startsWith("pianostream://local/") == true) {
            return uncached
        }
        val parsed = uriString?.let(PianoStreamUri::parse)
        if (parsed is PianoStreamUri.Id.Stream && downloadedProvider(parsed.videoId)) {
            return uncached
        }
        return cached
    }

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        uncached.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val d = delegateFor(dataSpec.uri)
        active = d
        return d.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active!!.read(buffer, offset, length)

    override fun getUri(): android.net.Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        active?.close()
        active = null
    }
}
