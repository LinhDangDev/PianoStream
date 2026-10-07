package com.pianostream.core.youtube.client

/**
 * Registry host allow-list for player-config fetches — the same four hosts
 * baked into `:app` BuildConfig.REGISTRY_HOSTS. Frozen as a code constant here
 * because Gradle files are 1a-owned and may not be edited to add a module-level
 * BuildConfig field after the freeze commit.
 *
 *  - `raw.githubusercontent.com` — SimpMusic `maxrave-dev/simpmusic-files` mirror
 *  - `cdn.jsdelivr.net`, `github.com`, `objects.githubusercontent.com` —
 *    Faraday `MetrolistGroup/faraday` releases
 */
internal object RegistryHosts {
    val allowed: Set<String> = setOf(
        "raw.githubusercontent.com",
        "cdn.jsdelivr.net",
        "github.com",
        "objects.githubusercontent.com",
    )

    /** Default registry locations, tried in order. */
    val candidateUrls: List<String> = listOf(
        "https://raw.githubusercontent.com/maxrave-dev/simpmusic-files/main/player_configs.json",
        "https://cdn.jsdelivr.net/gh/MetrolistGroup/faraday@main/player_configs.json",
    )

    fun isAllowed(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return url.startsWith("https://") && host != null &&
            (host in allowed || allowed.any { host == it || host.endsWith(".$it") })
    }
}
