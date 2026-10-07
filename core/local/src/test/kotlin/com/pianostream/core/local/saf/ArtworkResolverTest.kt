package com.pianostream.core.local.saf

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.local.scan.ArtworkResolver
import org.junit.Test

class ArtworkResolverTest {

    private fun doc(name: String) = SafDocument(
        docId = "id:$name", uri = "content://x/$name", displayName = name,
        mimeType = "image/jpeg", size = 100, lastModified = 0,
        kind = SafDocumentKind.JPEG,
    )

    @Test
    fun front_beats_back_beats_hash_beats_stray() {
        val folder = listOf(doc("Back.jpeg"), doc("Front.jpeg"), doc("a".repeat(32) + ".jpg"))
        assertThat(ArtworkResolver.resolve(folder, listOf(doc("stray.jpg"))))
            .isEqualTo("content://x/Front.jpeg")

        val noFront = listOf(doc("Back.jpeg"), doc("a".repeat(32) + ".jpg"))
        assertThat(ArtworkResolver.resolve(noFront, listOf(doc("stray.jpg"))))
            .isEqualTo("content://x/Back.jpeg")

        val hashOnly = listOf(doc("a".repeat(32) + ".jpg"))
        assertThat(ArtworkResolver.resolve(hashOnly, listOf(doc("stray.jpg"))))
            .isEqualTo("content://x/" + "a".repeat(32) + ".jpg")

        assertThat(ArtworkResolver.resolve(emptyList(), listOf(doc("stray.jpg"))))
            .isEqualTo("content://x/stray.jpg")
        assertThat(ArtworkResolver.resolve(emptyList(), emptyList())).isNull()
    }

    @Test
    fun front_match_is_case_insensitive() {
        val folder = listOf(doc("FRONT.JPEG"))
        assertThat(ArtworkResolver.resolve(folder, emptyList()))
            .isEqualTo("content://x/FRONT.JPEG")
    }
}
