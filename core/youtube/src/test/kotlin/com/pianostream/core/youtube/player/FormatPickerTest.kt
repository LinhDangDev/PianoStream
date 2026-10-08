package com.pianostream.core.youtube.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** F11: audio-itag allow-list 251 → 140 → 250 → 141, preferred honored first. */
class FormatPickerTest {

    private fun fmt(itag: Int) = FormatPicker.Format(
        itag = itag,
        url = "https://x/$itag",
        signatureCipher = null,
        mimeType = "audio/x",
        bitrate = 1000,
        contentLength = 1L,
    )

    @Test
    fun `picks 251 first in canonical order`() {
        val out = FormatPicker.pick(listOf(fmt(140), fmt(251), fmt(22)), preferred = 251)
        assertThat(out!!.itag).isEqualTo(251)
    }

    @Test
    fun `falls back to 140 when 251 absent`() {
        val out = FormatPicker.pick(listOf(fmt(140), fmt(22)), preferred = 251)
        assertThat(out!!.itag).isEqualTo(140)
    }

    @Test
    fun `then 250 then 141`() {
        assertThat(
            FormatPicker.pick(listOf(fmt(141), fmt(250)), preferred = 251)!!.itag
        ).isEqualTo(250)
        assertThat(
            FormatPicker.pick(listOf(fmt(141)), preferred = 251)!!.itag
        ).isEqualTo(141)
    }

    @Test
    fun `preferred override wins over canonical order`() {
        val out = FormatPicker.pick(listOf(fmt(251), fmt(140)), preferred = 140)
        assertThat(out!!.itag).isEqualTo(140)
    }

    @Test
    fun `rejects formats outside the allow-list`() {
        assertThat(FormatPicker.pick(listOf(fmt(22), fmt(18), fmt(599)), preferred = 251))
            .isNull()
    }

    @Test
    fun `empty list yields null`() {
        assertThat(FormatPicker.pick(emptyList(), preferred = 251)).isNull()
    }

    @Test
    fun `pickVideo auto picks the best mp4 adaptive rung`() {
        // Auto == top of the ladder (137): best mp4 wins over webm and lower tiers.
        val out = FormatPicker.pickVideo(
            listOf(fmt(136), fmt(248), fmt(137), fmt(251)),
            preferred = 137,
        )
        assertThat(out!!.itag).isEqualTo(137)
    }

    @Test
    fun `pickVideo never picks muxed or audio itags`() {
        // 22/18 are muxed progressive (double audio inside the merged
        // source), 251/140 are audio-only — the video path rejects all four.
        assertThat(
            FormatPicker.pickVideo(
                listOf(fmt(22), fmt(18), fmt(251), fmt(140)),
                preferred = 137,
            ),
        ).isNull()
        assertThat(FormatPicker.pickVideo(emptyList(), preferred = 137)).isNull()
    }

    @Test
    fun `pickVideo at-or-below preferred tier never upgrades`() {
        // 720p request on a 1080p+480p shelf → 480p, never the 1080p rungs.
        val out = FormatPicker.pickVideo(
            listOf(fmt(137), fmt(248), fmt(135)),
            preferred = 136,
        )
        assertThat(out!!.itag).isEqualTo(135)
    }

    @Test
    fun `pickVideo prefers mp4 over webm within a tier`() {
        val out = FormatPicker.pickVideo(
            listOf(fmt(247), fmt(136), fmt(134)),
            preferred = 136,
        )
        assertThat(out!!.itag).isEqualTo(136)
    }

    @Test
    fun `pickVideo falls back to same-tier webm when mp4 rung absent`() {
        val out = FormatPicker.pickVideo(
            listOf(fmt(248), fmt(243), fmt(135)),
            preferred = 137,
        )
        assertThat(out!!.itag).isEqualTo(248)
    }

    @Test
    fun `pickVideo exact preferred itag wins over ordering`() {
        val out = FormatPicker.pickVideo(
            listOf(fmt(137), fmt(134), fmt(243)),
            preferred = 243,
        )
        assertThat(out!!.itag).isEqualTo(243)
    }

    @Test
    fun `pickVideo floors at the lowest available when all tiers are above`() {
        // 144p request on a 360p-minimum video → lowest adaptive, mp4 first.
        val out = FormatPicker.pickVideo(
            listOf(fmt(243), fmt(134), fmt(137)),
            preferred = 160,
        )
        assertThat(out!!.itag).isEqualTo(134)
    }

    @Test
    fun `pickVideo preferred outside the ladder behaves like auto`() {
        val out = FormatPicker.pickVideo(
            listOf(fmt(136), fmt(248)),
            preferred = 999,
        )
        assertThat(out!!.itag).isEqualTo(248)
    }

    @Test
    fun `heightOf maps every ladder itag`() {
        assertThat(FormatPicker.heightOf(137)).isEqualTo(1080)
        assertThat(FormatPicker.heightOf(248)).isEqualTo(1080)
        assertThat(FormatPicker.heightOf(160)).isEqualTo(144)
        assertThat(FormatPicker.heightOf(278)).isEqualTo(144)
        assertThat(FormatPicker.heightOf(22)).isNull()
        assertThat(FormatPicker.heightOf(251)).isNull()
    }
}
