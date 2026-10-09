package com.pianostream.app.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM contract for [ShareIntents.shareTextFor] — the YouTube link form
 * for `stream`/`video` ids, the `"title — artist"` form for local items.
 */
class ShareIntentsTest {

    @Test
    fun `stream mediaId shares the youtube music watch link`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://stream/dQw4w9WgXcQ",
                title = "Never Gonna Give You Up",
                artist = "Rick Astley",
            ),
        ).isEqualTo(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ" +
                " — Never Gonna Give You Up by Rick Astley",
        )
    }

    @Test
    fun `video mediaId shares the same youtube music watch link`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://video/abcdefghij0",
                title = "Song",
                artist = "Artist",
            ),
        ).isEqualTo("https://music.youtube.com/watch?v=abcdefghij0 — Song by Artist")
    }

    @Test
    fun `youtube item with null artist drops the by-clause`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://stream/dQw4w9WgXcQ",
                title = "Song",
                artist = null,
            ),
        ).isEqualTo("https://music.youtube.com/watch?v=dQw4w9WgXcQ — Song")
    }

    @Test
    fun `youtube item with blank artist drops the by-clause`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://stream/dQw4w9WgXcQ",
                title = "Song",
                artist = "  ",
            ),
        ).isEqualTo("https://music.youtube.com/watch?v=dQw4w9WgXcQ — Song")
    }

    @Test
    fun `youtube watch url shares the youtube music link`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDxyz",
                title = "Song",
                artist = "Artist",
            ),
        ).isEqualTo("https://music.youtube.com/watch?v=dQw4w9WgXcQ — Song by Artist")
    }

    @Test
    fun `youtu be short link shares the youtube music link`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "https://youtu.be/dQw4w9WgXcQ",
                title = "Song",
                artist = "Artist",
            ),
        ).isEqualTo("https://music.youtube.com/watch?v=dQw4w9WgXcQ — Song by Artist")
    }

    @Test
    fun `local mediaId shares title and artist text only`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://local/disc3.t2",
                title = "Local FLAC",
                artist = "Local Artist",
            ),
        ).isEqualTo("Local FLAC — Local Artist")
    }

    @Test
    fun `local item with null artist shares just the title`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://local/disc3.t2",
                title = "Local FLAC",
                artist = null,
            ),
        ).isEqualTo("Local FLAC")
    }

    @Test
    fun `content uri source shares title and artist text only`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "content://com.android.externalstorage.documents/tree/primary%3AMusic/doc/abc",
                title = "Track",
                artist = "Someone",
            ),
        ).isEqualTo("Track — Someone")
    }

    @Test
    fun `empty source shares title and artist text only`() {
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "",
                title = "Track",
                artist = "Someone",
            ),
        ).isEqualTo("Track — Someone")
    }

    @Test
    fun `malformed pianostream uri shares title and artist text only`() {
        // parse() rejects ids with reserved characters → local text form.
        assertThat(
            ShareIntents.shareTextFor(
                uriOrMediaId = "pianostream://stream/bad%2Fid",
                title = "Track",
                artist = "Someone",
            ),
        ).isEqualTo("Track — Someone")
    }
}
