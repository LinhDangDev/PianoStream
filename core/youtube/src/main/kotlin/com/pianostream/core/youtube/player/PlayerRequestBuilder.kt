package com.pianostream.core.youtube.player

import com.pianostream.core.youtube.client.YouTubeClientCatalog
import com.pianostream.core.youtube.innertube.InnerTubeHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/** Builds the `/youtubei/v1/player` request body for a catalog client. */
internal object PlayerRequestBuilder {

    fun build(
        client: YouTubeClientCatalog.Client,
        videoId: String,
        visitorData: String? = InnerTubeHeaders.visitorData,
        cpn: String? = null,
        poToken: String? = null,
        sts: String? = null,
    ): JsonObject = buildJsonObject {
        put("videoId", videoId)
        cpn?.let { put("cpn", it) }
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", client.name)
                put("clientVersion", client.version)
                client.clientScreen?.let { put("clientScreen", it) }
                client.platform?.let { put("platform", it) }
                visitorData?.let { put("visitorData", it) }
                client.deviceMake?.let { put("deviceMake", it) }
                client.deviceModel?.let { put("deviceModel", it) }
                client.osName?.let { put("osName", it) }
                client.osVersion?.let { put("osVersion", it) }
                if (client.androidSdkVersion > 0) {
                    put("androidSdkVersion", client.androidSdkVersion)
                }
                put("hl", "en")
                put("gl", "US")
                put("utcOffsetMinutes", 0)
                sts?.let { put("signatureTimestamp", it.toIntOrNull() ?: 0) }
            }
            putJsonObject("request") {
                putJsonArray("internalExperimentFlags") {}
                put("useSsl", true)
            }
            putJsonObject("user") {
                put("lockedSafetyMode", false)
            }
        }
        putJsonObject("playbackContext") {
            putJsonObject("contentPlaybackContext") {
                put("html5Preference", "HTML5_PREF_WANTS")
                sts?.let { put("signatureTimestamp", it.toIntOrNull() ?: 0) }
            }
        }
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        // Session-bound poToken rides the player request; the GVS token never
        // goes here — it decorates only the final googlevideo URL (F3).
        poToken?.let {
            put("serviceIntegrityDimensions", buildJsonObject {
                put("poToken", it)
            })
        }
    }

    /**
     * Context-only body for `POST /visitor_id` — the real apps fetch a bound
     * visitor token this way before their first `/player` call.
     */
    fun buildContextOnly(client: YouTubeClientCatalog.Client): JsonObject =
        buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    client.clientScreen?.let { put("clientScreen", it) }
                    client.platform?.let { put("platform", it) }
                    client.deviceMake?.let { put("deviceMake", it) }
                    client.deviceModel?.let { put("deviceModel", it) }
                    client.osName?.let { put("osName", it) }
                    client.osVersion?.let { put("osVersion", it) }
                    if (client.androidSdkVersion > 0) {
                        put("androidSdkVersion", client.androidSdkVersion)
                    }
                    put("hl", "en")
                    put("gl", "US")
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
        }
}
