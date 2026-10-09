package com.pianostream.core.media.lyrics

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-level lyrics cache — an access-ordered in-memory LRU plus a disk
 * mirror under `cacheDir/lyrics/<sha256(mediaId)>.lrc`.
 *
 * The cached payload is the RAW lyric text (synced `.lrc` or plain words),
 * byte-exact from the source; callers re-derive [Lyrics] via
 * [LrcParser.toLyrics]. Misses are not cached: a `null` lookup must stay
 * eligible for a later fetch (a track may gain lyrics after library rescan).
 *
 * Pure file/map logic — unit tests point [diskDir] at a temp folder.
 */
class LyricsCache(
    private val diskDir: File,
    private val maxEntries: Int = 64,
) {

    constructor(context: Context) : this(File(context.cacheDir, "lyrics"))

    private val mutex = Mutex()
    private val memory = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    /** Memory first, then disk (a disk hit promotes back into memory). */
    suspend fun get(mediaId: String): String? = mutex.withLock {
        memory[mediaId]?.let { return it }
        val file = fileFor(mediaId)
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        memory[mediaId] = raw
        raw
    }

    /** Writes through to memory and disk; disk failure keeps the memory hit. */
    suspend fun put(mediaId: String, raw: String) = mutex.withLock {
        memory[mediaId] = raw
        runCatching {
            diskDir.mkdirs()
            fileFor(mediaId).writeText(raw)
        }
    }

    internal fun fileFor(mediaId: String): File =
        File(diskDir, "${sha256Hex(mediaId)}.lrc")

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
