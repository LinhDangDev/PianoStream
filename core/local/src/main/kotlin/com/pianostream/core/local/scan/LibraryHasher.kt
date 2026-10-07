package com.pianostream.core.local.scan

import java.security.MessageDigest

/**
 * `contentKey` = stable hash of `(folderPath, indexInCue)` (B3/CC2) —
 * survives `clearLibrary()` + rescan; never a Room rowid. 32 lowercase-hex
 * chars satisfies `PianoStreamUri.isValidId` ([A-Za-z0-9._~-] ≤ 64).
 */
object LibraryHasher {

    fun contentKey(folderPath: String, indexInCue: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$folderPath|$indexInCue".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 32)
    }

    /** `pianostream://local/<contentKey>` mediaId for a track. */
    fun mediaId(folderPath: String, indexInCue: Int): String =
        "pianostream://local/" + contentKey(folderPath, indexInCue)

    /** Stable per-disc key (artwork cache slot, `discId` extras). */
    fun discKey(folderPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(folderPath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 32)
    }
}
