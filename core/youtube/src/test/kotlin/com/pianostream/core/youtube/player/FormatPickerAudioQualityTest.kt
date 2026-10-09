package com.pianostream.core.youtube.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Audio-quality tiers (AUTO/LOW/NORMAL/HIGH → null/250/140/251): a tier's
 * preferred itag is ordered first but the full ranked allow-list is retained
 * as fallback — candidates are never dropped.
 */
class FormatPickerAudioQualityTest {

    private fun fmt(itag: Int) = FormatPicker.Format(
        itag = itag,
        url = "https://x/$itag",
        signatureCipher = null,
        mimeType = "audio/x",
        bitrate = 1000,
        contentLength = 1L,
    )

    @Test
    fun `every tier itag is inside the audio allow-list`() {
        assertThat(FormatPicker.ALLOWED_ITAGS).containsAtLeast(250, 140, 251)
    }

    @Test
    fun `low tier itag 250 wins over canonical first`() {
        val out = FormatPicker.pick(
            listOf(fmt(251), fmt(250), fmt(140)),
            preferred = 250,
        )
        assertThat(out!!.itag).isEqualTo(250)
    }

    @Test
    fun `normal tier itag 140 wins over canonical first`() {
        val out = FormatPicker.pick(
            listOf(fmt(251), fmt(140)),
            preferred = 140,
        )
        assertThat(out!!.itag).isEqualTo(140)
    }

    @Test
    fun `high tier itag 251 wins over canonical order`() {
        val out = FormatPicker.pick(
            listOf(fmt(140), fmt(250), fmt(251)),
            preferred = 251,
        )
        assertThat(out!!.itag).isEqualTo(251)
    }

    @Test
    fun `missing preferred itag keeps the full ranked fallback`() {
        // LOW (250) requested on a shelf without it — canonical 251 → 140
        // order still applies; candidates are never dropped.
        assertThat(
            FormatPicker.pick(listOf(fmt(140), fmt(251)), preferred = 250)!!.itag,
        ).isEqualTo(251)
        assertThat(
            FormatPicker.pick(listOf(fmt(140)), preferred = 250)!!.itag,
        ).isEqualTo(140)
        assertThat(
            FormatPicker.pick(listOf(fmt(141), fmt(250)), preferred = 251)!!.itag,
        ).isEqualTo(250)
    }

    @Test
    fun `no allowed audio format yields null`() {
        assertThat(FormatPicker.pick(listOf(fmt(22), fmt(18)), preferred = 140))
            .isNull()
        assertThat(FormatPicker.pick(emptyList(), preferred = 251)).isNull()
    }
}
