package com.pianostream.core.designsystem.art

import android.net.Uri

/**
 * Single choke point every artwork URI passes through before reaching Coil
 * (F9): `https` only on the pinned host allow-list, `content://` only from
 * the persisted SAF tree providers, everything else rejected — `file://`,
 * `http://`, foreign `content://` authorities, and bare paths never load.
 */
object ArtworkUrls {

    private val HTTPS_HOSTS = setOf(
        "i.ytimg.com",
        "lh3.googleusercontent.com",
        "yt3.ggpht.com",
        "music.youtube.com",
    )

    /** SAF providers the library scanner accepts trees from (F8/F9). */
    private val SAF_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.google.android.providers.media.documents",
    )

    /**
     * Returns the URI unchanged when loadable, or `null` when it must never
     * reach the image loader (F9). Never throws.
     */
    fun sanitize(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return when (parsed.scheme?.lowercase()) {
            "https" -> if (parsed.host?.lowercase() in HTTPS_HOSTS) uri else null
            "content" ->
                if (parsed.authority?.lowercase() in SAF_AUTHORITIES) uri else null
            else -> null // file://, http://, unknown schemes
        }
    }
}
