package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Routes `pianostream://local/…` opens to the plain (uncached) resolving
 * source and everything else to the `CacheDataSource`. Local FLAC bytes are
 * never streamed through `SimpleCache` (a 21 GB library would obliterate the
 * 512 MiB streaming budget); `ContentIdCacheKeyFactory` returning `null` for
 * `local/` stays a dead-code contract assertion instead of a live NPE.
 */
class SchemeRoutingDataSource(
    private val cached: DataSource,
    private val uncached: DataSource,
) : DataSource {

    private var active: DataSource? = null

    private fun delegateFor(uri: android.net.Uri?): DataSource =
        if (uri?.toString()?.startsWith("pianostream://local/") == true) {
            uncached
        } else {
            cached
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
