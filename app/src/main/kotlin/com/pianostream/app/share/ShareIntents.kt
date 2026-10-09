package com.pianostream.app.share

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.pianostream.core.data.uri.PianoStreamUri
import java.io.File

/**
 * "Now Playing — Share" (Spotify-style: system share sheet with the track
 * link for YouTube items, the audio file itself for local ones).
 *
 * Two entry points:
 *
 *  * [shareTextFor] — pure formatter. `pianostream://stream/<id>` and
 *    `pianostream://video/<id>` (plus raw youtube.com/youtu.be links) share
 *    the canonical `https://music.youtube.com/watch?v=<id>` link; everything
 *    else shares `"<title> — <artist>"` text.
 *
 *  * [shareTrack] — builds and launches an `ACTION_SEND` chooser. YouTube
 *    items go as `text/plain` (link + title); local items whose [sourceUri]
 *    is a `content://` URI or an existing file path go as an audio stream
 *    `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` + `EXTRA_TEXT`. Any
 *    launch failure (no handler, `FileUriExposedException` on `file://` URIs,
 *    a non-exported `content://` grant) falls back to the text share; when
 *    even that fails the user gets [NO_HANDLER_MESSAGE]. Never throws.
 *
 * [artworkUri] is attached to the chooser's [ClipData] when it is a
 * `content://` URI so receiving apps can render a thumbnail and inherit the
 * read grant; it is never the primary shared payload.
 */
public object ShareIntents {

    /** Canonical YouTube Music watch-link prefix for stream/video ids. */
    public const val YOUTUBE_MUSIC_WATCH_URL: String = "https://music.youtube.com/watch?v="

    /** Toast text when no activity on the device can handle the share. */
    public const val NO_HANDLER_MESSAGE: String = "Nothing can handle share"

    /**
     * Display text for a shared track.
     *
     *  * YouTube (`pianostream://stream|video/<id>`, or a
     *    youtube.com / music.youtube.com / youtu.be link carrying the id):
     *    `"<yt-music link> — <title> by <artist>"`.
     *  * Local items (`pianostream://local/<key>`, `content://`, file path,
     *    anything else): `"<title> — <artist>"`.
     *
     * A null/blank [artist] is dropped from either form rather than rendered
     * literally.
     */
    public fun shareTextFor(uriOrMediaId: String, title: String, artist: String?): String {
        val youtubeId = youtubeIdOf(uriOrMediaId)
        if (youtubeId != null) {
            val byArtist = artist?.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty()
            return "$YOUTUBE_MUSIC_WATCH_URL$youtubeId — $title$byArtist"
        }
        val dashArtist = artist?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        return "$title$dashArtist"
    }

    /**
     * Launches the system share sheet for a track. [sourceUri] decides the
     * payload: pass the item's `mediaId`/`pianostream://` URI (YouTube →
     * link share) or the local FLAC's real `content://` URI / file path
     * (→ audio stream share); `null` shares title/artist text only.
     */
    public fun shareTrack(
        context: Context,
        title: String,
        artist: String?,
        sourceUri: String?,
        artworkUri: String? = null,
    ) {
        val text = shareTextFor(sourceUri.orEmpty(), title, artist)
        val stream = if (youtubeIdOf(sourceUri) == null) shareableLocalUri(sourceUri) else null

        if (stream != null) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, stream)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipDataOf(stream, artworkUri)?.let { clipData = it }
            }
            if (launchChooser(context, send, title)) return
            // Stream share failed (no handler, file:// exposure, dead grant):
            // fall through to the text share, never crash.
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            clipDataOf(null, artworkUri)?.let { clipData = it }
        }
        if (!launchChooser(context, send, title)) {
            runCatching {
                Toast.makeText(context, NO_HANDLER_MESSAGE, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** `startActivity` guarded: any failure returns false so the caller can fall back. */
    private fun launchChooser(context: Context, send: Intent, chooserTitle: String): Boolean {
        val chooser = Intent.createChooser(send, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            // ActivityNotFoundException (nothing handles the type),
            // FileUriExposedException (file:// stream on API 24+),
            // SecurityException (grant the resolver rejects).
            false
        }
    }

    /**
     * The YouTube video id behind [value]: a `pianostream://stream|video/<id>`
     * id/URI (parsed with the CC3 contract so hostile input yields `null`),
     * or a `?v=` / path id on a youtube.com / youtu.be link. `null` otherwise.
     * Regex, not `android.net.Uri`, so [shareTextFor] stays JVM-pure.
     */
    private fun youtubeIdOf(value: String?): String? {
        if (value.isNullOrBlank()) return null
        when (val id = PianoStreamUri.parse(value)) {
            is PianoStreamUri.Id.Stream -> return id.videoId
            is PianoStreamUri.Id.Video -> return id.videoId
            else -> Unit
        }
        YOUTU_BE_ID.find(value)?.let { return it.groupValues[1] }
        if (YOUTUBE_HOST.containsMatchIn(value)) {
            YOUTUBE_WATCH_ID.find(value)?.let { return it.groupValues[1] }
        }
        return null
    }

    private val YOUTU_BE_ID = Regex("^https?://youtu\\.be/([A-Za-z0-9_-]{1,64})")
    private val YOUTUBE_HOST = Regex("^https?://([A-Za-z0-9-]+\\.)?youtube\\.com/")
    private val YOUTUBE_WATCH_ID = Regex("[?&]v=([A-Za-z0-9_-]{1,64})")

    /**
     * [sourceUri] as a stream-shareable [Uri], or `null`: accepts `content://`
     * (SAF FLACs — the grant flag conveys read) and `file://` or raw paths of
     * files that exist (a `file://` failure is caught at launch and falls
     * back to text). `pianostream://local/<key>` mediaIds are NOT real file
     * URIs and intentionally return `null` — the caller must pass the FLAC's
     * content URI to get the audio stream share.
     */
    private fun shareableLocalUri(sourceUri: String?): Uri? {
        if (sourceUri.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT,
            ContentResolver.SCHEME_FILE,
            -> uri
            null -> sourceUri.takeIf { File(it).exists() }?.let { Uri.fromFile(File(it)) }
            else -> null
        }
    }

    /**
     * ClipData carrying [stream] (so the chooser propagates the read grant to
     * the picked target) plus [artwork] when it is a `content://` URI
     * (thumbnail for the receiver). `null` when there is nothing to attach.
     */
    private fun clipDataOf(stream: Uri?, artwork: String?): ClipData? {
        val items = mutableListOf<Uri>()
        stream?.let(items::add)
        artwork?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme?.lowercase() == ContentResolver.SCHEME_CONTENT }
            ?.let(items::add)
        if (items.isEmpty()) return null
        val clip = ClipData.newRawUri(null, items[0])
        items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }
}
