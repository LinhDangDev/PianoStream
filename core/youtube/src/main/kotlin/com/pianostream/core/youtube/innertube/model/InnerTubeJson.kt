package com.pianostream.core.youtube.innertube.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lenient accessors over InnerTube's loosely-typed JSON; every getter returns
 *  null rather than throwing so malformed upstream shapes degrade to dropped
 *  items (F11), never crashes. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): List<JsonElement>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.toList()

internal fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNullSafe

internal fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

internal val JsonElement.contentOrNullSafe: String?
    get() = runCatching { jsonPrimitive.content }.getOrNull()

internal fun JsonObject?.textRuns(): String? {
    if (this == null) return null
    // {runs:[{text}...]} or {simpleText}
    str("simpleText")?.let { return it }
    val runs = arr("runs") ?: return null
    val sb = StringBuilder()
    for (r in runs) (r as? JsonObject)?.str("text")?.let(sb::append)
    return sb.takeIf { it.isNotEmpty() }?.toString()
}

internal fun JsonObject.navEndpoint(): JsonObject? =
    obj("navigationEndpoint") ?: this

internal fun JsonObject.findDeep(key: String): JsonElement? {
    this[key]?.let { return it }
    for ((_, v) in this) {
        when (v) {
            is JsonObject -> v.findDeep(key)?.let { return it }
            is kotlinx.serialization.json.JsonArray -> {
                for (e in v) (e as? JsonObject)?.findDeep(key)?.let { return it }
            }
            else -> Unit
        }
    }
    return null
}
