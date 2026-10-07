package com.pianostream.core.local.scan

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Tag-reading seam for loose audio files (singles). The scanner stays
 * Context-free: tests substitute a fake; production uses
 * [MetadataRetrieverTagReader]. Implementations must let `SecurityException`
 * propagate (M2 — a revoked tree grant aborts the whole scan, it is never a
 * per-file fallback).
 */
fun interface AudioTagReader {
    suspend fun read(uri: String): AudioTags?
}

/** Container/format tags of one loose audio document — every field optional. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val durationMs: Long? = null,
    val embeddedArt: ByteArray? = null,
)

/**
 * `ContentResolver`/`MediaMetadataRetriever` [AudioTagReader] over the SAF doc
 * URI (opened via `openAssetFileDescriptor`, never a `file://` path). Any
 * non-security failure yields `null` — the caller falls back to
 * filename/folder naming.
 */
class MetadataRetrieverTagReader(
    private val resolver: ContentResolver,
) : AudioTagReader {

    override suspend fun read(uri: String): AudioTags? {
        val afd = try {
            resolver.openAssetFileDescriptor(Uri.parse(uri), "r")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            afd.use { f ->
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.fileDescriptor)
                    AudioTags(
                        title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                        date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                        durationMs = r
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()?.takeIf { it > 0 },
                        embeddedArt = r.embeddedPicture,
                    )
                } finally {
                    r.release()
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
