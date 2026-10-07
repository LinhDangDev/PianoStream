package com.pianostream.core.youtube.innertube.model

import com.pianostream.core.youtube.api.YouTubeItem
import kotlinx.serialization.json.JsonObject

/**
 * Maps a single InnerTube renderer (`musicResponsiveListItemRenderer`,
 * `musicTwoRowItemRenderer`, `musicCardShelfRenderer`, `lockupRenderer`…) to a
 * [YouTubeItem]. Returns null when the item carries no usable media id or its
 * `videoId` fails the `^[A-Za-z0-9_-]{11}$` boundary gate (CC3/F11 —
 * non-conforming items are dropped here, never reach the app).
 */
internal object ItemParser {

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val BROWSE_ID = Regex("^[A-Za-z0-9_-]{2,64}$")

    fun parse(
        renderer: JsonObject,
        /** The wrapping key (e.g. `musicTwoRowItemRenderer`) — decides kind. */
        rendererKey: String? = null,
    ): YouTubeItem? {
        val kind = kindOf(renderer, rendererKey) ?: return null
        val videoId = firstVideoId(renderer)
        val browseId = firstBrowseId(renderer)
        if (videoId != null && !VIDEO_ID.matches(videoId)) return null
        if (videoId == null && browseId == null) return null
        if (browseId != null && !BROWSE_ID.matches(browseId)) return null

        val title = firstText(renderer, "title")
            ?: renderer.obj("header")?.obj("musicCardShelfHeaderBasicRenderer")?.textRuns()
            ?: return null
        val subtitle = subtitleOf(renderer)
        val durationMs = durationMsOf(renderer)
        val artwork = artworkOf(renderer)

        return YouTubeItem(
            kind = kind,
            videoId = videoId,
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            artworkUri = artwork,
            durationMs = durationMs,
        )
    }

    private fun kindOf(r: JsonObject, rendererKey: String? = null): YouTubeItem.Kind? {
        // Heuristic by renderer key + shape.
        if (r.containsKey("musicTwoRowItemRenderer") ||
            r.containsKey("musicMultiRowMenuItemRenderer") ||
            rendererKey == "musicTwoRowItemRenderer" ||
            rendererKey == "musicMultiRowMenuItemRenderer"
        ) {
            val nav = r.findDeep("watchPlaylistEndpoint") ?: r.findDeep("watchEndpoint")
            val browse = r.findDeep("browseEndpoint")?.let { it as? JsonObject }
            if (nav != null) return YouTubeItem.Kind.PLAYLIST
            val pageType = browse?.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")?.str("pageType")
            return when {
                pageType?.contains("ALBUM") == true -> YouTubeItem.Kind.ALBUM
                pageType?.contains("ARTIST") == true -> YouTubeItem.Kind.ARTIST
                pageType?.contains("PLAYLIST") == true -> YouTubeItem.Kind.PLAYLIST
                else -> YouTubeItem.Kind.ALBUM
            }
        }
        val hasVideo =
            r.findDeep("watchEndpoint") != null || r.findDeep("playlistItemData") != null ||
                r.findDeep("videoId") != null
        if (!hasVideo) return null
        // Track vs video: YT Music marks songs with MUSIC_VIDEO_TYPE_ATV or
        // an explicit song type; videos use MUSIC_VIDEO_TYPE_OMV/UGC.
        val songType =
            r.findDeep("musicVideoType")?.contentOrNullSafe
        return when {
            songType?.contains("ATV") == true -> YouTubeItem.Kind.TRACK
            songType != null -> YouTubeItem.Kind.VIDEO
            else -> YouTubeItem.Kind.TRACK // music surface default
        }
    }

    private fun firstVideoId(r: JsonObject): String? =
        r.findDeep("videoId")?.contentOrNullSafe
            ?: r.obj("playlistItemData")?.str("videoId")
            ?: r.obj("onTap")?.obj("watchEndpoint")?.str("videoId")

    private fun firstBrowseId(r: JsonObject): String? =
        (r.findDeep("browseEndpoint") as? JsonObject)?.str("browseId")
            ?: (r.findDeep("watchPlaylistEndpoint") as? JsonObject)
                ?.str("playlistId")

    private fun firstText(r: JsonObject, key: String): String? =
        r.obj(key)?.textRuns()
            ?: r.arr("flexColumns")
                ?.firstOrNull()?.let { it as? JsonObject }
                ?.findDeep("text")?.let { it as? JsonObject }?.textRuns()

    private fun subtitleOf(r: JsonObject): String? =
        r.obj("subtitle")?.textRuns()
            ?: r.arr("flexColumns")?.getOrNull(1)
                ?.let { it as? JsonObject }?.findDeep("text")
                ?.let { it as? JsonObject }?.textRuns()

    private fun durationMsOf(r: JsonObject): Long? {
        val text = r.arr("fixedColumns")?.firstOrNull()
            ?.let { it as? JsonObject }?.findDeep("text")
            ?.let { it as? JsonObject }?.textRuns()
            ?: r.findDeep("lengthText")?.let { it as? JsonObject }?.textRuns()
            ?: r.findDeep("lengthSeconds")?.contentOrNullSafe?.let {
                return it.toLongOrNull()?.times(1000)
            } ?: return null
        return clockTextToMs(text)
    }

    internal fun clockTextToMs(text: String): Long? {
        val parts = text.trim().split(":").map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> null
        }
    }

    private fun artworkOf(r: JsonObject): String? {
        val thumbs = r.findDeep("thumbnails") as? kotlinx.serialization.json.JsonArray
        val last = thumbs?.lastOrNull() as? JsonObject
        val url = last?.str("url") ?: return null
        // Never emit file:// artwork (SEC-F).
        return url.takeIf { it.startsWith("https://") }
    }
}
