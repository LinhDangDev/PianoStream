package com.pianostream.core.youtube.spotify

import com.pianostream.core.youtube.innertube.model.arr
import com.pianostream.core.youtube.innertube.model.findDeep
import com.pianostream.core.youtube.innertube.model.long
import com.pianostream.core.youtube.innertube.model.obj
import com.pianostream.core.youtube.innertube.model.str
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Public-Spotify-playlist importer — no API key, no login. Given an
 * `open.spotify.com/playlist/<id>` link it fetches the public embed page
 * (`/embed/playlist/<id>`), which embeds the full track list as JSON inside
 * `<script id="__NEXT_DATA__">`, and maps it onto [ImportedTrack]s.
 *
 * Parsing is deliberately defensive (Spotify reshuffles this blob):
 *  1. exact path `props.pageProps.state.data.entity`,
 *  2. deep-walk for any object carrying a `trackList`/`entityType:"playlist"`
 *     shape,
 *  3. deep-walk for the legacy `tracks.items[].track` shape
 *     (`name`/`artists[].name`/`duration_ms`/`album.images`).
 *
 * The embed caps a playlist at ~100 tracks — [MAX_TRACKS] truncates at the
 * same bound so the UI's "up to 100" promise is honest.
 */
public class SpotifyPlaylistImporter(
    private val okHttp: OkHttpClient = defaultClient(),
    /** Overridable for MockWebServer tests. */
    private val embedBase: String = "https://open.spotify.com",
) {

    /** One Spotify track as extracted from the embed JSON. */
    public data class ImportedTrack(
        val title: String,
        /** Comma-joined artist names ("KAROL G, Judeline, rusowsky"). */
        val artist: String?,
        val durationMs: Long?,
        /** Track art when the embed carries it (current format does not). */
        val artworkUrl: String?,
    )

    /** The parsed playlist. [totalCount] is the embed's reported track count
     * (== `tracks.size` today — the embed ships no separate total). */
    public data class ImportedPlaylist(
        val id: String,
        val name: String,
        /** Playlist owner/author line ("Spotify"), when present. */
        val owner: String?,
        val coverUrl: String?,
        val tracks: List<ImportedTrack>,
        val totalCount: Int,
    )

    /** Typed failure so the UI can phrase the error without string sniffing. */
    public class SpotifyImportException(
        public val kind: Kind,
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause) {
        public enum class Kind {
            /** Input wasn't a recognisable Spotify playlist link/id. */
            INVALID_URL,

            /** Transport-level failure (DNS/TLS/timeout/IO). */
            NETWORK,

            /** 404/410 — private, deleted, or never existed. */
            NOT_FOUND_OR_PRIVATE,

            /** Page fetched but no track JSON could be located/parsed. */
            PARSE,
        }
    }

    /** What a pasted Spotify link points at. */
    public enum class EntityKind {
        PLAYLIST,
        ALBUM,
        TRACK,
        /** `spotify.link/<code>` mobile share link — resolves at import time. */
        SHORT,
    }

    /** A parsed Spotify entity link (id + what it points at). */
    public data class ParsedTarget(
        val id: String,
        val kind: EntityKind,
    )

    /** The playlist id inside a pasted link/uri, or null when it isn't one. */
    public fun parsePlaylistId(input: String): String? =
        parseSpotifyTarget(input)
            ?.takeIf { it.kind == EntityKind.PLAYLIST }
            ?.id

    /**
     * Any Spotify entity link/uri/id: `open.spotify.com/[intl-xx/](playlist|
     * album|track)/<id>[?si=…]`, `spotify:(playlist|album|track):<id>`, a bare
     * 22-char id (treated as a playlist), or a `spotify.link/<code>` share
     * link ([EntityKind.SHORT] — the id field then holds the link itself).
     */
    public fun parseSpotifyTarget(input: String): ParsedTarget? {
        val q = input.trim()
        if (q.isEmpty()) return null
        if (SHORT_LINK.matches(q) || SHORT_LINK.find(q)?.let { it.range.first == 0 } == true) {
            return ParsedTarget(q, EntityKind.SHORT)
        }
        if (PLAYLIST_ID.matches(q)) return ParsedTarget(q, EntityKind.PLAYLIST)
        URI_PATTERN.find(q)?.let {
            return ParsedTarget(it.groupValues[2], kindOf(it.groupValues[1]))
        }
        if (q.contains("spotify.com") || q.contains("spotify:")) {
            URL_PATTERN.find(q)?.let {
                return ParsedTarget(it.groupValues[2], kindOf(it.groupValues[1]))
            }
        }
        return null
    }

    private fun kindOf(word: String): EntityKind = when (word.lowercase()) {
        "album" -> EntityKind.ALBUM
        "track" -> EntityKind.TRACK
        else -> EntityKind.PLAYLIST
    }

    /** One valid link pulled from the multi-link paste field. */
    public data class ParsedLink(
        /** Entity id — feeds [import]; for [EntityKind.SHORT] this is the link. */
        val id: String,
        /** The token as pasted (for the per-link status list). */
        val raw: String,
        /** 1-based line it came from. */
        val line: Int,
        /** What the link points at. */
        val kind: EntityKind = EntityKind.PLAYLIST,
    )

    /** A line (or tokens of it) no playlist link could be parsed from. */
    public data class InvalidLine(
        val line: Int,
        /** The offending text — the token(s) that didn't parse. */
        val text: String,
    )

    /** Result of [parseImportLinks]: usable links + flagged lines. */
    public data class ParsedLinks(
        val links: List<ParsedLink>,
        val invalid: List<InvalidLine>,
    )

    /**
     * Multi-link input parsing: one link per line, but whitespace- and
     * comma-separated tokens on a line are each validated on their own
     * (links carry neither). Every token goes through [parsePlaylistId];
     * duplicates of an already-accepted playlist id are dropped silently,
     * tokens that don't parse flag their line (valid tokens on the same
     * line still count).
     */
    public fun parseImportLinks(input: String): ParsedLinks {
        val links = mutableListOf<ParsedLink>()
        val invalid = mutableListOf<InvalidLine>()
        val seen = HashSet<String>()
        input.lines().forEachIndexed { i, rawLine ->
            val tokens = rawLine.split(TOKEN_SPLIT).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@forEachIndexed
            val bad = mutableListOf<String>()
            for (token in tokens) {
                val target = parseSpotifyTarget(token)
                when {
                    target == null -> bad += token
                    // dedupe on entity kind+id; short links dedupe on the token
                    seen.add(
                        if (target.kind == EntityKind.SHORT) "short:$token"
                        else "${target.kind}:${target.id}",
                    ) -> links += ParsedLink(target.id, token, line = i + 1, target.kind)
                    // repeat of an earlier link — dropped, not flagged
                }
            }
            if (bad.isNotEmpty()) {
                invalid += InvalidLine(line = i + 1, text = bad.joinToString(" "))
            }
        }
        return ParsedLinks(links, invalid)
    }

    /** Fetches + parses an entity's embed page (playlist/album/track id or
     *  any supported link form, including `spotify.link` share links). */
    public suspend fun import(urlOrId: String): ImportedPlaylist =
        withContext(Dispatchers.IO) {
            var target = parseSpotifyTarget(urlOrId)
                ?: throw SpotifyImportException(
                    SpotifyImportException.Kind.INVALID_URL,
                    "not a Spotify link",
                )
            if (target.kind == EntityKind.SHORT) {
                target = parseSpotifyTarget(resolveShortLink(target.id))
                    ?: throw SpotifyImportException(
                        SpotifyImportException.Kind.INVALID_URL,
                        "spotify.link didn't point at a playlist/album/track",
                    )
            }
            val path = when (target.kind) {
                EntityKind.ALBUM -> "album"
                EntityKind.TRACK -> "track"
                else -> "playlist"
            }
            val html = get("$embedBase/embed/$path/${target.id}")
            val parsed = SpotifyEmbedParser.parse(html, target.kind)
                ?: throw SpotifyImportException(
                    SpotifyImportException.Kind.PARSE,
                    "no track list found in embed page",
                )
            parsed.copy(id = target.id)
        }

    /** Same as [import] but takes a [ParsedLink] from [parseImportLinks]. */
    public suspend fun import(link: ParsedLink): ImportedPlaylist =
        if (link.kind == EntityKind.SHORT) import(link.id) else
            withContext(Dispatchers.IO) {
                val path = when (link.kind) {
                    EntityKind.ALBUM -> "album"
                    EntityKind.TRACK -> "track"
                    else -> "playlist"
                }
                val html = get("$embedBase/embed/$path/${link.id}")
                val parsed = SpotifyEmbedParser.parse(html, link.kind)
                    ?: throw SpotifyImportException(
                        SpotifyImportException.Kind.PARSE,
                        "no track list found in embed page",
                    )
                parsed.copy(id = link.id)
            }

    /**
     * `spotify.link/<code>` is a Branch.io smart link: a server-side 307 sends
     * mobile user agents to the destination (open.spotify.com/… for a real
     * link, an app-store deepview for a dead one). Fetch with a mobile UA and
     * redirects off → take the Location; when the body is an interstitial
     * instead, scan it for an open.spotify.com entity URL too.
     */
    private fun resolveShortLink(url: String): String {
        val full = if (url.startsWith("http")) url else "https://$url"
        val request = Request.Builder()
            .url(full)
            .header("User-Agent", MOBILE_USER_AGENT)
            .get()
            .build()
        val client = okHttp.newBuilder().followRedirects(false).build()
        try {
            client.newCall(request).execute().use { resp ->
                val location = resp.header("Location")
                if (location != null && URL_PATTERN.containsMatchIn(location)) {
                    return location
                }
                resp.body?.string()?.let { body ->
                    URL_PATTERN.find(body)?.let { return it.value }
                }
                throw SpotifyImportException(
                    SpotifyImportException.Kind.INVALID_URL,
                    "spotify.link resolved nowhere usable (HTTP ${resp.code})",
                )
            }
        } catch (e: SpotifyImportException) {
            throw e
        } catch (e: IOException) {
            throw SpotifyImportException(
                SpotifyImportException.Kind.NETWORK,
                "spotify.link resolution failed",
                e,
            )
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        try {
            okHttp.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful ->
                        return resp.body?.string()
                            ?: throw SpotifyImportException(
                                SpotifyImportException.Kind.PARSE,
                                "empty body",
                            )
                    resp.code == 404 || resp.code == 410 ->
                        throw SpotifyImportException(
                            SpotifyImportException.Kind.NOT_FOUND_OR_PRIVATE,
                            "playlist is private or does not exist",
                        )
                    else ->
                        throw SpotifyImportException(
                            SpotifyImportException.Kind.NETWORK,
                            "embed page HTTP ${resp.code}",
                        )
                }
            }
        } catch (e: IOException) {
            throw SpotifyImportException(
                SpotifyImportException.Kind.NETWORK,
                "embed page fetch failed",
                e,
            )
        }
    }

    public companion object {
        /** Spotify embeds cap the track list at ~100 rows. */
        public const val MAX_TRACKS: Int = 100

        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Multi-link field separator — links contain no whitespace/commas. */
        private val TOKEN_SPLIT = Regex("[\\s,]+")

        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 " +
                "Mobile/15E148 Safari/604.1"

        // 22-char base62 id (Spotify ids are alnum, usually exactly 22).
        private val PLAYLIST_ID = Regex("^[A-Za-z0-9]{22}$")
        private val URI_PATTERN = Regex(
            "spotify:(playlist|album|track):([A-Za-z0-9]+)",
            RegexOption.IGNORE_CASE,
        )
        // open.spotify.com[/intl-xx[/...]]/(playlist|album|track)/<id>[?si=...]
        private val URL_PATTERN = Regex(
            "open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)*(playlist|album|track)/([A-Za-z0-9]+)",
        )
        // spotify.link/<code> — mobile share short link (Branch.io).
        private val SHORT_LINK = Regex(
            "(?:https?://)?spotify\\.link/[A-Za-z0-9]+/?",
            RegexOption.IGNORE_CASE,
        )

        public fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Parses the embed page HTML into an [SpotifyPlaylistImporter.ImportedPlaylist]
 * (`id` left blank — the caller knows it). Exposed separately so tests hit
 * fixtures without a web server.
 */
internal object SpotifyEmbedParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val NEXT_DATA = Regex(
        "<script\\s+id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parse(
        html: String,
        kind: SpotifyPlaylistImporter.EntityKind =
            SpotifyPlaylistImporter.EntityKind.PLAYLIST,
    ): SpotifyPlaylistImporter.ImportedPlaylist? {
        val blob = NEXT_DATA.find(html)?.groupValues?.get(1)?.trim()
            ?: return null
        val root = runCatching {
            json.parseToJsonElement(blob) as? JsonObject
        }.getOrNull() ?: return null

        val entity = findEntity(root, kind) ?: return null
        val name = entity.str("name") ?: entity.str("title") ?: return null
        val cover = firstImage(entity.obj("coverArt")?.arr("sources"))
            ?: entity.obj("visualIdentity")?.arr("image")
                ?.let { firstImage(it) }
        if (kind == SpotifyPlaylistImporter.EntityKind.TRACK) {
            val artists = entity.arr("artists")
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                ?.joinToString(", ")?.takeIf { it.isNotBlank() }
                ?: entity.str("subtitle")?.let(::clean)?.takeIf { it.isNotBlank() }
            return SpotifyPlaylistImporter.ImportedPlaylist(
                id = "",
                name = clean(name),
                owner = artists,
                coverUrl = cover,
                tracks = listOf(
                    SpotifyPlaylistImporter.ImportedTrack(
                        title = clean(name),
                        artist = artists,
                        durationMs = entity.long("duration")
                            ?: entity.long("duration_ms"),
                        artworkUrl = cover,
                    ),
                ),
                totalCount = 1,
            )
        }
        val tracks = parseTrackList(entity)
            ?: parseLegacyTracks(entity)
            ?: return null
        return SpotifyPlaylistImporter.ImportedPlaylist(
            id = "",
            name = clean(name),
            owner = entity.str("subtitle")?.let(::clean)?.takeIf { it.isNotBlank() },
            coverUrl = cover,
            tracks = tracks.take(SpotifyPlaylistImporter.MAX_TRACKS),
            totalCount = tracks.size,
        )
    }

    /** `props.pageProps.state.data.entity`, else a deep-walk for the same
     * shape (a `trackList` array, or `type`/`entityType` matching the kind). */
    private fun findEntity(
        root: JsonObject,
        kind: SpotifyPlaylistImporter.EntityKind,
    ): JsonObject? {
        root.obj("props")?.obj("pageProps")?.obj("state")?.obj("data")
            ?.obj("entity")?.let { return it }
        val typeWord = when (kind) {
            SpotifyPlaylistImporter.EntityKind.ALBUM -> "album"
            SpotifyPlaylistImporter.EntityKind.TRACK -> "track"
            else -> "playlist"
        }
        return deepFind(root) { o ->
            o["trackList"] != null ||
                o.str("type") == typeWord ||
                o.str("entityType") == typeWord
        }
    }

    private fun deepFind(
        node: JsonObject,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject? {
        if (predicate(node)) return node
        for ((_, v) in node) {
            when (v) {
                is JsonObject -> deepFind(v, predicate)?.let { return it }
                is kotlinx.serialization.json.JsonArray -> {
                    for (e in v) {
                        (e as? JsonObject)
                            ?.let { deepFind(it, predicate) }
                            ?.let { return it }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    /** Current shape: `entity.trackList[] = {title, subtitle, duration(ms)}`. */
    private fun parseTrackList(
        entity: JsonObject,
    ): List<SpotifyPlaylistImporter.ImportedTrack>? {
        val list = entity.arr("trackList") ?: return null
        return list.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val title = t.str("title")?.let(::clean) ?: return@mapNotNull null
            SpotifyPlaylistImporter.ImportedTrack(
                title = title,
                artist = t.str("subtitle")?.let(::clean)?.takeIf { it.isNotBlank() },
                durationMs = t.long("duration"),
                artworkUrl = t.obj("album")?.arr("images")?.let { firstImage(it) }
                    ?: t.arr("images")?.let { firstImage(it) },
            )
        }
    }

    /** Legacy shape: `entity.tracks.items[].track = {name, artists[].name,
     * duration_ms, album.images[]}`. */
    private fun parseLegacyTracks(
        entity: JsonObject,
    ): List<SpotifyPlaylistImporter.ImportedTrack>? {
        val items = entity.obj("tracks")?.arr("items")
            ?: entity.findDeep("items")
                ?.let { it as? kotlinx.serialization.json.JsonArray }?.toList()
            ?: return null
        return items.mapNotNull { el ->
            val t = (el as? JsonObject)?.obj("track") ?: return@mapNotNull null
            val title = (t.str("name") ?: t.str("title"))?.let(::clean)
                ?: return@mapNotNull null
            val artists = t.arr("artists")
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
            SpotifyPlaylistImporter.ImportedTrack(
                title = title,
                artist = artists,
                durationMs = t.long("duration_ms") ?: t.long("duration"),
                artworkUrl = t.obj("album")?.arr("images")?.let { firstImage(it) },
            )
        }
    }

    /** Spotify subtitles use NBSP/narrow-NBSP around commas — normalize so
     *  display text and search queries carry plain spaces. */
    private fun clean(s: String): String =
        s.replace('\u00A0', ' ').replace('\u202F', ' ').trim()

    private fun firstImage(sources: List<*>?): String? {
        val o = sources?.firstOrNull() as? JsonObject ?: return null
        return o.str("url")?.takeIf { it.startsWith("https://") }
    }
}
