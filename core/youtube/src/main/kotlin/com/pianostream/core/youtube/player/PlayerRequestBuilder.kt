package com.pianostream.core.youtube.player

import com.pianostream.core.youtube.client.YouTubeClientCatalog
import com.pianostream.core.youtube.innertube.InnerTubeHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Builds the `/youtubei/v1/player` request body for a catalog client. */
internal object PlayerRequestBuilder {

    fun build(
        client: YouTubeClientCatalog.Client,
        videoId: String,
        visitorData: String = InnerTubeHeaders.visitorData,
        poToken: String? = null,
        sts: String? = null,
    ): JsonObject = buildJsonObject {
        put("videoId", videoId)
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", client.name)
                put("clientVersion", client.version)
                put("hl", "en")
                put("gl", "US")
                put("visitorData", visitorData)
                sts?.let { put("signatureTimestamp", it.toIntOrNull() ?: 0) }
                if (client.name == "ANDROID_VR") {
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("androidSdkVersion", 32)
                }
                if (client.name == "IOS") {
                    put("deviceMake", "Apple")
                    put("deviceModel", "Apple Vision Pro")
                }
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
}
