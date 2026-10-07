package com.pianostream.core.data.uri

/**
 * PianoStream media identity URIs (Interface Freeze — CC2, CC3, SEC-F11).
 *
 * Two schemes identify a playable item everywhere in the app and on disk:
 *
 *  * `pianostream://stream/<videoId>` — remote identity. This string is also the
 *    `MediaItem.mediaId` and the `SimpleCache` key base. `itag` NEVER appears in
 *    a mediaId: it lives only inside
 *    `com.pianostream.core.youtube.api.ResolvedStream` as a transport detail
 *    chosen at resolve time, so a `preferredItag` change or a `251→140`
 *    fallback never orphans favourites/history/queue rows (B2).
 *
 *  * `pianostream://local/<contentKey>` — local identity. `contentKey` is a
 *    stable hash of `(folderPath, discRelativeIndex)` — never a Room rowid —
 *    so identity survives `clearLibrary()` + rescan (B3). `:core:media` expands
 *    it via `discs`/`tracks` rows to a `content://` FLAC URI plus a
 *    `ClippingConfiguration(startMs, endMs)`; the URI's own path component is
 *    never trusted for resolution (CC9).
 *
 * Validation contract: `parse` must round-trip (`parse(build(x)) == x`) and
 * rejects `/`, `..`, any `%` sequence (e.g. `%2F`), `?`, `#`, empty ids and
 * ids longer than 64 characters. The stricter videoId shape
 * `^[A-Za-z0-9_-]{11}$` is enforced at the `YouTubeClient`/parser boundary
 * (non-conforming items are dropped from results before an id ever reaches
 * this type); use [requireVideoId] for that check.
 */
public object PianoStreamUri {

    public const val SCHEME: String = "pianostream"
    public const val HOST_STREAM: String = "stream"
    public const val HOST_LOCAL: String = "local"

    private const val PREFIX: String = "$SCHEME://"
    private const val MAX_ID_LENGTH: Int = 64

    private val ID_CHARACTERS = Regex("[A-Za-z0-9._~-]+")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** A parsed PianoStream URI. */
    public sealed class Id {
        /** `pianostream://stream/<videoId>` */
        public data class Stream(val videoId: String) : Id()

        /** `pianostream://local/<contentKey>` */
        public data class Local(val contentKey: String) : Id()
    }

    /** Builds `pianostream://stream/<videoId>`. Throws on an invalid id. */
    public fun stream(videoId: String): String {
        require(isValidId(videoId)) { "invalid stream id component" }
        return PREFIX + HOST_STREAM + "/" + videoId
    }

    /** Builds `pianostream://local/<contentKey>`. Throws on an invalid id. */
    public fun local(contentKey: String): String {
        require(isValidId(contentKey)) { "invalid local id component" }
        return PREFIX + HOST_LOCAL + "/" + contentKey
    }

    /**
     * Parses a `pianostream://` URI into an [Id], or returns `null` when the
     * value is not a well-formed PianoStream URI (wrong scheme/host, missing
     * or extra path segments, or an id component failing the CC3 validation
     * rules). Never throws on hostile input.
     */
    public fun parse(value: String): Id? {
        if (!value.startsWith(PREFIX)) return null
        val rest = value.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        val host = rest.substring(0, slash)
        val id = rest.substring(slash + 1)
        if (!isValidId(id)) return null
        return when (host) {
            HOST_STREAM -> Id.Stream(id)
            HOST_LOCAL -> Id.Local(id)
            else -> null
        }
    }

    /** True when `value` is a `pianostream://stream/` URI. */
    public fun isStream(value: String): Boolean = parse(value) is Id.Stream

    /** True when `value` is a `pianostream://local/` URI. */
    public fun isLocal(value: String): Boolean = parse(value) is Id.Local

    /**
     * Returns the videoId of a `stream` id when it satisfies the strict
     * `^[A-Za-z0-9_-]{11}$` boundary shape, else `null`.
     */
    public fun requireVideoId(videoId: String): String? =
        if (VIDEO_ID.matches(videoId)) videoId else null

    /**
     * Generic id-component validation (CC3): non-empty, at most 64 chars, and
     * free of `/`, `..`, `%`, `?`, `#` and any character outside
     * `[A-Za-z0-9._~-]`. Rejecting `%` wholesale also covers `%2F` and every
     * other traversal-by-encoding attempt.
     */
    public fun isValidId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_ID_LENGTH) return false
        if (!ID_CHARACTERS.matches(id)) return false
        if (id.contains("..")) return false
        return true
    }
}
