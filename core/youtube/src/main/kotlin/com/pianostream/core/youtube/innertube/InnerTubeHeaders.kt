package com.pianostream.core.youtube.innertube

import okhttp3.Request

/**
 * WEB_REMIX (InnerTube client id 67) request headers.
 *
 * `visitorData` is generated once per install, held in memory only, and never
 * persisted to DataStore/disk (CC10/F12). It is a random base64url token in the
 * same shape as a real visitor token — not tied to an account.
 */
internal object InnerTubeHeaders {
    const val CLIENT_NAME = "WEB_REMIX"
    const val CLIENT_ID = "67"
    const val CLIENT_VERSION = "1.20250929.01.00"
    const val ORIGIN = "https://music.youtube.com"

    @Volatile
    var visitorData: String = mintVisitorData()
        private set

    private fun mintVisitorData(): String {
        val bytes = ByteArray(11).also { java.security.SecureRandom().nextBytes(it) }
        // java.util.Base64 so the mint is exercisable in plain-JVM unit tests.
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun applyTo(builder: Request.Builder): Request.Builder = builder
        .header("X-Goog-Api-Format-Version", "1")
        .header("X-YouTube-Client-Name", CLIENT_ID)
        .header("X-YouTube-Client-Version", CLIENT_VERSION)
        .header("Origin", ORIGIN)
        .header("X-Origin", ORIGIN)
        .header("Referer", "$ORIGIN/")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("X-Goog-Visitor-Id", visitorData)
}
