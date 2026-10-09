package com.pianostream.core.youtube.innertube

import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import com.pianostream.core.youtube.api.YouTubePage
import com.pianostream.core.youtube.api.YouTubeSection
import com.pianostream.core.youtube.innertube.model.ItemParser
import com.pianostream.core.youtube.innertube.model.arr
import com.pianostream.core.youtube.innertube.model.findDeep
import com.pianostream.core.youtube.innertube.model.obj
import com.pianostream.core.youtube.innertube.model.str
import com.pianostream.core.youtube.innertube.model.textRuns
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `WEB_REMIX` InnerTube client over the `music.youtube.com` `youtubei/v1/` endpoints.
 * No API key, no YouTube Data API. Every emitted [YouTubeItem] passed the
 * videoId boundary gate in [ItemParser] (CC3/F11). Failures surface as
 * [ExtractionException] with redacted messages (CC4/F13); `get_search_suggestions`
 * is gated by the consumer on the local-scope rule (F12) — this class itself
 * performs the call only when invoked.
 */
internal class InnerTubeClientImpl(
    private val okHttp: OkHttpClient = defaultClient(),
    /** Overridable for MockWebServer tests. */
    private val base: String = "https://music.youtube.com/youtubei/v1",
) : YouTubeClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        continuation: String?,
    ): YouTubePage = post("search") {
        put("query", query)
        continuation?.let { put("continuation", it) }
            ?: SearchParams.forFilter(filter)?.let { put("params", it) }
    }.let { parsePage(it) }

    override suspend fun browse(browseId: String, continuation: String?): YouTubePage =
        post("browse") {
            put("browseId", browseId)
            continuation?.let { put("continuation", it) }
        }.let { parsePage(it) }

    override suspend fun suggestions(query: String): List<String> =
        post("get_search_suggestions") {
            put("input", query)
        }.let { body ->
            mutableListOf<String>().also { collectSuggestions(body, it) }
        }

    private fun collectSuggestions(node: JsonObject, out: MutableList<String>) {
        for ((k, v) in node) {
            when (v) {
                is JsonObject -> {
                    if (k == "searchSuggestionRenderer") {
                        v.obj("suggestion")?.textRuns()?.let(out::add)
                    }
                    collectSuggestions(v, out)
                }
                is kotlinx.serialization.json.JsonArray ->
                    v.forEach { (it as? JsonObject)?.let { e -> collectSuggestions(e, out) } }
                else -> Unit
            }
        }
    }

    private suspend fun post(
        endpoint: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", InnerTubeHeaders.CLIENT_NAME)
                    put("clientVersion", InnerTubeHeaders.CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    put("visitorData", InnerTubeHeaders.visitorData)
                }
            }
            body()
        }.toString()

        val request = InnerTubeHeaders.applyTo(
            Request.Builder().url("$base/$endpoint")
        ).post(payload.toRequestBody(JSON)).build()

        try {
            okHttp.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful ->
                        resp.body?.string()?.let { json.parseToJsonElement(it) }
                            as? JsonObject
                            ?: throw ExtractionException.Network(
                                "empty body from youtubei/$endpoint",
                            )
                    resp.code == 429 -> throw ExtractionException.RateLimited()
                    resp.code in 400..499 -> throw ExtractionException.Network(
                        "youtubei/$endpoint HTTP ${resp.code}",
                    )
                    else -> throw ExtractionException.Network(
                        "youtubei/$endpoint HTTP ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            throw ExtractionException.Network("youtubei/$endpoint failed", e)
        }
    }

    /** Map a search/browse response body onto the frozen [YouTubePage] shape. */
    private fun parsePage(body: JsonObject): YouTubePage {
        val sections = mutableListOf<YouTubeSection>()
        collectSections(body, sections)
        val continuation = body.findDeep("continuationCommand")
            ?.let { it as? JsonObject }?.str("token")
            ?: body.findDeep("continuations")?.let { c ->
                (c as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.obj("nextContinuationData")?.str("continuation")
            }
        return YouTubePage(
            title = pageTitle(body),
            sections = sections,
            continuation = continuation,
        )
    }

    /**
     * Playlist pages put the real title in `microformatDataRenderer.title`;
     * the first bare `title` in the body is the sort menu's ("Sort"). Fall
     * back to a header renderer's title, then to the deep scan (search
     * pages have no microformat block).
     */
    private fun pageTitle(body: JsonObject): String? =
        body.findDeep("microformatDataRenderer")
            ?.let { it as? JsonObject }?.str("title")
            ?: headerTitle(body)
            ?: body.findDeep("title")?.let { it as? JsonObject }?.textRuns()

    private fun headerTitle(node: JsonObject): String? {
        for (key in headerRenderers) {
            node[key]?.let { it as? JsonObject }
                ?.obj("title")?.textRuns()?.let { return it }
        }
        for ((_, v) in node) {
            when (v) {
                is JsonObject -> headerTitle(v)?.let { return it }
                is kotlinx.serialization.json.JsonArray ->
                    v.forEach { (it as? JsonObject)?.let { e -> headerTitle(e)?.let { t -> return t } } }
                else -> Unit
            }
        }
        return null
    }

    private fun collectSections(node: JsonObject, out: MutableList<YouTubeSection>) {
        // A section is any musicShelfRenderer / musicCardShelfRenderer /
        // musicImmersiveCarouselShelfRenderer block with contents. Current
        // search responses also return itemSectionRenderer containers holding
        // the same per-item renderer objects directly. VL<playlistId> browse
        // pages use musicPlaylistShelfRenderer for their track list.
        for (key in shelfRenderers) {
            node[key]?.let { it as? JsonObject }?.let { shelf ->
                val items = parseItems(
                    shelf.arr("contents") ?: shelf.arr("items") ?: emptyList()
                )
                if (items.isNotEmpty()) {
                    out += YouTubeSection(
                        title = shelf.obj("title")?.textRuns(),
                        items = items,
                    )
                }
            }
        }
        // Continuation pages return bare item lists with no shelf wrapper:
        // `appendContinuationItemsAction.continuationItems`, or a
        // `*Continuation` object's `contents` (e.g.
        // playlistVideoListContinuation / musicShelfContinuation).
        node.arr("continuationItems")?.let { addBareItems(it, out) }
        for ((k, v) in node) {
            if (k.endsWith("Continuation")) {
                (v as? JsonObject)?.arr("contents")?.let { addBareItems(it, out) }
            }
        }
        for ((_, v) in node) {
            when (v) {
                is JsonObject -> collectSections(v, out)
                is kotlinx.serialization.json.JsonArray ->
                    v.forEach { (it as? JsonObject)?.let { e -> collectSections(e, out) } }
                else -> Unit
            }
        }
    }

    /** Parse a bare renderer-element list into an untitled section. */
    private fun addBareItems(contents: List<kotlinx.serialization.json.JsonElement>, out: MutableList<YouTubeSection>) {
        val items = parseItems(contents)
        if (items.isNotEmpty()) out += YouTubeSection(title = null, items = items)
    }

    private fun parseItems(contents: List<kotlinx.serialization.json.JsonElement>): List<YouTubeItem> {
        val items = mutableListOf<YouTubeItem>()
        for (c in contents) {
            val element = c as? JsonObject ?: continue
            val rendererKey = element.keys.firstOrNull() ?: continue
            val renderer = element[rendererKey] as? JsonObject ?: continue
            ItemParser.parse(renderer, rendererKey)?.let(items::add)
        }
        return items
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        val shelfRenderers = listOf(
            "musicShelfRenderer",
            "musicCardShelfRenderer",
            "musicImmersiveCarouselShelfRenderer",
            "musicCarouselShelfRenderer",
            "musicPlaylistShelfRenderer",
            "itemSectionRenderer",
        )

        /** Header blocks carrying the page title on browse pages. */
        val headerRenderers = listOf(
            "musicResponsiveHeaderRenderer",
            "musicEditablePlaylistDetailHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicImmersiveHeaderRenderer",
            "musicVisualHeaderRenderer",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
