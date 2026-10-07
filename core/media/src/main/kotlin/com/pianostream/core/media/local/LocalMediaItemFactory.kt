package com.pianostream.core.media.local

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.local.api.LocalTrackWindow
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds the per-track local `MediaItem` (`pianostream://local/<contentKey>`)
 * with a `ClippingConfiguration` — one MediaItem per track, never a
 * per-disc `ConcatenatingMediaSource2` (CC1).
 *
 *  - `endMs` omitted when `null`/`C.TIME_UNSET` or `> flacDurationMs`
 *    (M3/CC11).
 *  - Stamps `discId`/`flacUri`/`contentKey` into `MediaMetadata.extras` for
 *    the synchronous crossfade predicate (H2).
 *  - E3 lockscreen art: reads the disc's artwork once via `ContentResolver`,
 *    caches bytes under `filesDir/artwork/<discContentKey>.jpg`, and stamps
 *    `MediaMetadata.setArtworkData` — the lockscreen process has no SAF grant
 *    so a `content://` URI alone would render blank.
 */
class LocalMediaItemFactory(
    private val context: Context,
    private val artworkDir: File = File(context.filesDir, "artwork"),
) {

    private val artworkMutex = Mutex()

    /** Extra keys read by the crossfade predicate (H2). */
    companion object {
        const val EXTRA_DISC_ID = "pianostream.discId"
        const val EXTRA_FLAC_URI = "pianostream.flacUri"
        const val EXTRA_CONTENT_KEY = "pianostream.contentKey"
        const val EXTRA_GAIN_DB = "pianostream.gainDb"
        const val EXTRA_PEAK = "pianostream.peak"

        /** Reads the flacUri the predicate fades on (null = unknown → never fade). */
        fun flacUriOf(item: MediaItem?): String? =
            item?.mediaMetadata?.extras?.getString(EXTRA_FLAC_URI)
    }

    /**
     * @param window the resolved track window (from `LocalTrackWindowSource`)
     * @param discId the disc's contentKey (artwork cache slot)
     * @param artworkDocumentUri SAF URI of the disc's artwork file (Front.jpeg
     *        order — resolved by Phase 3's library rows, never from the URI)
     * @param flacDurationMs real FLAC container duration when known
     * @param title/artist/durationMs denormalized display fields (CC6)
     */
    suspend fun build(
        window: LocalTrackWindow,
        discId: String,
        artworkDocumentUri: String?,
        flacDurationMs: Long?,
        title: String,
        artist: String?,
        durationMs: Long?,
    ): MediaItem {
        val mediaId = PianoStreamUri.local(window.contentKey)

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(window.startMs)
            .apply {
                // M3/CC11 — omit endMs entirely when unknown/invalid.
                val end = window.endMs
                if (end != null && end != C.TIME_UNSET &&
                    (flacDurationMs == null || end <= flacDurationMs)
                ) {
                    setEndPositionMs(end)
                }
            }
            .build()

        val extras: Bundle = bundleOf(
            EXTRA_DISC_ID to discId,
            EXTRA_FLAC_URI to window.flacUri,
            EXTRA_CONTENT_KEY to window.contentKey,
        ).apply {
            window.gainDb?.let { putDouble(EXTRA_GAIN_DB, it) }
            window.peak?.let { putDouble(EXTRA_PEAK, it) }
        }

        // Cache-first even without an artwork document: the library scan writes
        // a single's embedded art into this slot (no content:// art exists).
        val artworkBytes = artworkBytesFor(discId, artworkDocumentUri)

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setExtras(extras)
            .apply {
                // artworkUri for in-process readers (vinyl labels, queue
                // thumbs — Coil holds the SAF grant); artworkData stays for
                // the grant-less lockscreen process.
                artworkDocumentUri?.let { setArtworkUri(Uri.parse(it)) }
                artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaId) // resolved below the cache by the datasource stack
            .setMediaMetadata(metadata)
            .setClippingConfiguration(clipping)
            .build()
    }

    /**
     * E3 — per-disc artwork-byte cache under `filesDir/artwork/`. Returns the
     * decoded-and-recoded JPEG bytes, or null when unreadable. [documentUri]
     * may be null (a single whose only art is the scan-written embedded-art
     * cache entry): cache hit still serves, null + no cache → null.
     */
    internal suspend fun artworkBytesFor(discId: String, documentUri: String?): ByteArray? =
        artworkMutex.withLock {
            val cached = File(artworkDir, "$discId.jpg")
            if (cached.exists()) return@withLock cached.readBytes()
            if (documentUri == null) return@withLock null
            val bytes = runCatching {
                context.contentResolver.openInputStream(Uri.parse(documentUri))
                    ?.use { it.readBytes() }
            }.getOrNull() ?: return@withLock null
            // Normalize to JPEG bytes (validates the payload is an image).
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull() ?: return@withLock null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            artworkDir.mkdirs()
            val data = out.toByteArray()
            runCatching { cached.writeBytes(data) }
            data
        }
}
