package com.pianostream.core.youtube.client

/**
 * The ordered client catalog for `/youtubei/v1/player` requests. Client data is
 * just data — adding a client is a one-line change (client retirement / version
 * drift is expected breakage, H).
 */
internal object YouTubeClientCatalog {

    enum class Lifecycle { STABLE, CANARY, EXPERIMENTAL, DEPRECATED, BROKEN }

    enum class Transport { DIRECT, HLS, SABR }

    data class Client(
        val id: String,
        val name: String,
        val version: String,
        val lifecycle: Lifecycle,
        val transport: Transport = Transport.DIRECT,
        val requiresAuth: Boolean = false,
        /** Some clients require a poToken bound to the video (GVS binding). */
        val requiresGvsPoToken: Boolean = false,
        val userAgent: String? = null,
        /**
         * Mobile clients post to `youtubei.googleapis.com` with
         * `X-Goog-Api-Format-Version: 2` and a rich `context.client` — the
         * fields below shape that context (mirroring what the real apps send;
         * missing fields read as a third-party client and get SABR-only or
         * bot-checked responses).
         */
        val mobile: Boolean = false,
        val platform: String? = null,
        val clientScreen: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val androidSdkVersion: Int = -1,
    )

    /**
     * Ordered fallback: `ANDROID_VR` → `VISIONOS` → `IOS` → older `ANDROID_VR`
     * → `ANDROID` → `WEB_REMIX`. The mobile clients return plain googlevideo
     * URLs (no cipher, no poToken) when the request carries a real
     * `visitorData` — the same strategy NewPipe uses. `ANDROID` is
     * EXPERIMENTAL: its adaptive formats are SABR-only now, but its muxed
     * formats still help video resolves. `WEB_REMIX` stays last as the
     * poToken fallback.
     */
    val ordered: List<Client> = listOf(
        Client(
            id = "ANDROID_VR_1_65_10",
            name = "ANDROID_VR",
            version = "1.65.10",
            lifecycle = Lifecycle.STABLE,
            userAgent =
                "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                    "(Linux; U; Android 13; unknown) gzip",
            mobile = true,
            platform = "MOBILE",
            clientScreen = "WATCH",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "13",
            androidSdkVersion = 33,
        ),
        Client(
            id = "VISIONOS",
            name = "VISIONOS",
            version = "1.02",
            lifecycle = Lifecycle.STABLE,
            userAgent =
                "com.google.visionos.youtube/1.02 (RealityDevice14,1; " +
                    "U; CPU visionOS 25_6_0 like Mac OS X; en_US)",
            mobile = true,
            platform = "MOBILE",
            clientScreen = "WATCH",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            osName = "visionOS",
            osVersion = "25.6.0.23O471",
        ),
        Client(
            id = "IOS_21_03_2",
            name = "IOS",
            version = "21.03.2",
            lifecycle = Lifecycle.STABLE,
            userAgent =
                "com.google.ios.youtube/21.03.2 (iPhone16,2; " +
                    "U; CPU iOS 18_7_2 like Mac OS X; en_US)",
            mobile = true,
            platform = "MOBILE",
            clientScreen = "WATCH",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iOS",
            osVersion = "18.7.2.22H124",
        ),
        Client(
            id = "ANDROID_VR_1_61_48",
            name = "ANDROID_VR",
            version = "1.61.48",
            lifecycle = Lifecycle.DEPRECATED,
            userAgent =
                "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                    "(Linux; U; Android 13; unknown) gzip",
            mobile = true,
            platform = "MOBILE",
            clientScreen = "WATCH",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "13",
            androidSdkVersion = 33,
        ),
        Client(
            id = "ANDROID_21_03_36",
            name = "ANDROID",
            version = "21.03.36",
            lifecycle = Lifecycle.EXPERIMENTAL,
            userAgent =
                "com.google.android.youtube/21.03.36 (Linux; U; Android 16) gzip",
            mobile = true,
            platform = "MOBILE",
            clientScreen = "WATCH",
            osName = "Android",
            osVersion = "16",
            androidSdkVersion = 36,
        ),
        Client(
            id = "WEB_REMIX",
            name = "WEB_REMIX",
            version = InnerTubeVersion.WEB_REMIX,
            lifecycle = Lifecycle.STABLE,
            // Not `requiresAuth` on purpose: signed-out requests carrying a
            // session poToken are the documented webpo pairing — they still
            // play when every mobile client gets bot-checked.
            requiresGvsPoToken = true,
        ),
    )

    fun byId(id: String): Client? = ordered.firstOrNull { it.id == id }
}

private object InnerTubeVersion {
    const val WEB_REMIX = "1.20260121.03.00"
}
