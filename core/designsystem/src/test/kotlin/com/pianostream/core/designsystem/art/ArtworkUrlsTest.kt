package com.pianostream.core.designsystem.art

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkUrlsTest {

    @Test
    fun rejects_file_http_and_foreign_content() {
        // F9: nothing outside the allow-lists ever reaches Coil.
        assertThat(ArtworkUrls.sanitize("file:///data/local/tmp/x.jpg")).isNull()
        assertThat(ArtworkUrls.sanitize("http://169.254.169.254/latest/meta.jpg"))
            .isNull()
        assertThat(
            ArtworkUrls.sanitize("content://evil.provider/root/x.jpg"),
        ).isNull()
        assertThat(ArtworkUrls.sanitize("https://i.imgur.com/x.png")).isNull()
        assertThat(ArtworkUrls.sanitize("")).isNull()
        assertThat(ArtworkUrls.sanitize(null)).isNull()
        assertThat(ArtworkUrls.sanitize("not a uri")).isNull()
    }

    @Test
    fun accepts_allowlisted_https_and_saf_content() {
        assertThat(ArtworkUrls.sanitize("https://i.ytimg.com/vi/x/hqdefault.jpg"))
            .isEqualTo("https://i.ytimg.com/vi/x/hqdefault.jpg")
        assertThat(ArtworkUrls.sanitize("https://lh3.googleusercontent.com/abc"))
            .isNotNull()
        assertThat(ArtworkUrls.sanitize("https://yt3.ggpht.com/abc")).isNotNull()
        assertThat(ArtworkUrls.sanitize("https://music.youtube.com/x")).isNotNull()
        assertThat(
            ArtworkUrls.sanitize(
                "content://com.android.externalstorage.documents/document/primary%3AFront.jpeg",
            ),
        ).isNotNull()
        assertThat(
            ArtworkUrls.sanitize(
                "content://com.google.android.providers.media.documents/document/1",
            ),
        ).isNotNull()
    }
}
