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
    )

    /**
     * Ordered fallback per the plan:
     * `VISIONOS` → `ANDROID_VR 1.65.10` → `ANDROID_VR 1.61.48` →
     * `ANDROID_VR 1.43.32` → `WEB_REMIX` (authenticated sessions only).
     */
    val ordered: List<Client> = listOf(
        Client(
            id = "VISIONOS",
            name = "IOS",
            version = "19.09.3",
            lifecycle = Lifecycle.EXPERIMENTAL,
            userAgent =
                "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)",
        ),
        Client(
            id = "ANDROID_VR_1_65_10",
            name = "ANDROID_VR",
            version = "1.65.10",
            lifecycle = Lifecycle.STABLE,
            userAgent =
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 13; unknown) gzip",
        ),
        Client(
            id = "ANDROID_VR_1_61_48",
            name = "ANDROID_VR",
            version = "1.61.48",
            lifecycle = Lifecycle.DEPRECATED,
            userAgent =
                "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 13; unknown) gzip",
        ),
        Client(
            id = "ANDROID_VR_1_43_32",
            name = "ANDROID_VR",
            version = "1.43.32",
            lifecycle = Lifecycle.DEPRECATED,
            userAgent =
                "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; unknown) gzip",
        ),
        Client(
            id = "WEB_REMIX",
            name = "WEB_REMIX",
            version = InnerTubeVersion.WEB_REMIX,
            lifecycle = Lifecycle.STABLE,
            requiresAuth = true,
            requiresGvsPoToken = true,
        ),
    )

    fun byId(id: String): Client? = ordered.firstOrNull { it.id == id }
}

private object InnerTubeVersion {
    const val WEB_REMIX = "1.20250929.01.00"
}
