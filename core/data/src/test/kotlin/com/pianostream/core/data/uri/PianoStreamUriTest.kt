package com.pianostream.core.data.uri

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.pianostream.core.data.uri.PianoStreamUri.Id
import org.junit.Test

/**
 * Adversarial-input coverage for the frozen URI contract (CC2/CC3/F11):
 * `parse` must round-trip `build`, and reject `/`, `..`, `%2F`, `?`, `#`,
 * empty ids and ids over 64 characters — without ever throwing.
 */
class PianoStreamUriTest {

    @Test
    fun `stream uri round-trips`() {
        val videoId = "dQw4w9WgXcQ"
        val uri = PianoStreamUri.stream(videoId)
        assertThat(uri).isEqualTo("pianostream://stream/$videoId")
        assertThat(PianoStreamUri.parse(uri)).isEqualTo(Id.Stream(videoId))
        assertThat(PianoStreamUri.isStream(uri)).isTrue()
        assertThat(PianoStreamUri.isLocal(uri)).isFalse()
    }

    @Test
    fun `local uri round-trips`() {
        val contentKey = "abc123def4567890abcd"
        val uri = PianoStreamUri.local(contentKey)
        assertThat(uri).isEqualTo("pianostream://local/$contentKey")
        assertThat(PianoStreamUri.parse(uri)).isEqualTo(Id.Local(contentKey))
        assertThat(PianoStreamUri.isLocal(uri)).isTrue()
    }

    @Test
    fun `video uri round-trips`() {
        val videoId = "dQw4w9WgXcQ"
        val uri = PianoStreamUri.video(videoId)
        assertThat(uri).isEqualTo("pianostream://video/$videoId")
        assertThat(PianoStreamUri.parse(uri)).isEqualTo(Id.Video(videoId))
        assertThat(PianoStreamUri.isVideo(uri)).isTrue()
        // A video URI is never its audio/local counterpart.
        assertThat(PianoStreamUri.isStream(uri)).isFalse()
        assertThat(PianoStreamUri.isLocal(uri)).isFalse()
        assertThat(PianoStreamUri.isVideo(PianoStreamUri.stream(videoId))).isFalse()
    }

    @Test
    fun `parse rejects hostile ids`() {
        val hostile = listOf(
            "pianostream://stream/",
            "pianostream://stream",
            "pianostream://stream/a/b",
            "pianostream://stream/../etc",
            "pianostream://stream/..",
            "pianostream://stream/..%2F..%2Fetc",
            "pianostream://stream/%2Fetc%2Fpasswd",
            "pianostream://stream/id?x=1",
            "pianostream://stream/id#frag",
            "pianostream://stream/id with space",
            "pianostream://stream/" + "A".repeat(65),
            "pianostream://stream/" + "A".repeat(1000),
            "pianostream://local/",
            "pianostream://local/a b",
            "pianostream://local/%252e%252e",
            "pianostream://video/",
            "pianostream://video",
            "pianostream://video/a/b",
            "pianostream://video/../etc",
            "pianostream://video/%2Fetc",
            "pianostream://video/id?x=1",
            "pianostream://video/id#frag",
            "pianostream://video/id with space",
            "pianostream://video/" + "A".repeat(65),
            "pianostream://video/abc/extra",
            "pianostream:///stream/abc",
            "pianostream://stream\\abc",
            "file://stream/abc",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "pianostream://other/abc",
            "pianostream://stream/abc/extra",
            "",
            "   ",
            "pianostream://",
            "notauri",
        )
        for (input in hostile) {
            assertWithMessage(input)
                .that(PianoStreamUri.parse(input))
                .isNull()
        }
    }

    @Test
    fun `build throws on invalid ids`() {
        val bad = listOf("", "a/b", "..", "..%2Fx", "id?x", "id#f", "A".repeat(65), "a b", "%41")
        for (id in bad) {
            assertThrowsOnBuild(id)
        }
    }

    private fun assertThrowsOnBuild(id: String) {
        val threw = runCatching { PianoStreamUri.stream(id) }.isFailure &&
            runCatching { PianoStreamUri.local(id) }.isFailure &&
            runCatching { PianoStreamUri.video(id) }.isFailure
        assertWithMessage(id).that(threw).isTrue()
    }

    @Test
    fun `boundary videoId shape is separate from uri validity`() {
        // Well-formed URI id but not a YouTube videoId (needs exactly 11 chars
        // of [A-Za-z0-9_-]): parse succeeds, requireVideoId rejects. The strict
        // shape is enforced at the YouTubeClient boundary, not in the URI type.
        val uri = PianoStreamUri.stream("abc")
        assertThat(PianoStreamUri.parse(uri)).isEqualTo(Id.Stream("abc"))
        assertThat(PianoStreamUri.requireVideoId("abc")).isNull()
        assertThat(PianoStreamUri.requireVideoId("dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(PianoStreamUri.requireVideoId("AAAA/itag")).isNull()
    }

    @Test
    fun `id characters allow dots and dashes but not traversal`() {
        assertThat(PianoStreamUri.parse("pianostream://local/a.b-c_d~e")).isNotNull()
        // ".." as substring is rejected even embedded in valid chars
        assertThat(PianoStreamUri.parse("pianostream://local/a..b")).isNull()
        assertThat(PianoStreamUri.parse("pianostream://local/a.b.c")).isNotNull()
    }
}
