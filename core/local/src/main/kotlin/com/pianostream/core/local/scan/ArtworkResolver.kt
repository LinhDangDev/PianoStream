package com.pianostream.core.local.scan

import com.pianostream.core.local.saf.SafDocument

/**
 * Artwork resolution order (measured on the real collection — 205 JPEGs =
 * 102 Front + 102 Back + 1 stray at the tree root):
 * `Front.jpeg` → `Back.jpeg` → hash-named jpeg in the disc folder → the
 * single stray jpeg at the tree root. Returns the SAF `content://` URI of
 * the winning document; null when none exists (E3/SEC: never `file://`).
 */
object ArtworkResolver {

    private val HASH_NAMED = Regex("^[0-9a-fA-F]{32,64}\\.(jpe?g)$", RegexOption.IGNORE_CASE)

    fun resolve(
        folderDocs: List<SafDocument>,
        rootDocs: List<SafDocument>,
    ): String? {
        val jpeg = { name: String ->
            folderDocs.firstOrNull {
                it.displayName.equals(name, ignoreCase = true)
            }?.uri
        }
        jpeg("Front.jpeg")?.let { return it }
        jpeg("Back.jpeg")?.let { return it }
        folderDocs.firstOrNull { HASH_NAMED.matches(it.displayName) }?.let {
            return it.uri
        }
        return rootDocs.firstOrNull {
            it.displayName.substringAfterLast('.', "")
                .lowercase() in setOf("jpeg", "jpg")
        }?.uri
    }
}
