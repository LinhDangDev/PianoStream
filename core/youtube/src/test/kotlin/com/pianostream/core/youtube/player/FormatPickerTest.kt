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
    fun `pickVideo prefers muxed 22 then 18`() {
        val out = FormatPicker.pickVideo(listOf(fmt(18), fmt(251), fmt(22)), preferred = 22)
        assertThat(out!!.itag).isEqualTo(22)
    }

    @Test
    fun `pickVideo falls back to 18 when 22 absent`() {
        val out = FormatPicker.pickVideo(listOf(fmt(251), fmt(18), fmt(140)), preferred = 22)
        assertThat(out!!.itag).isEqualTo(18)
    }

    @Test
    fun `pickVideo preferred override wins over canonical order`() {
        val out = FormatPicker.pickVideo(listOf(fmt(22), fmt(18)), preferred = 18)
        assertThat(out!!.itag).isEqualTo(18)
    }

    @Test
    fun `pickVideo rejects non-muxed and audio-only itags`() {
        // 251/140 are audio allow-list but not muxed video; 137/136 are
        // video-only adaptive (no audio track — not progressive).
        assertThat(
            FormatPicker.pickVideo(listOf(fmt(251), fmt(140), fmt(137), fmt(136)), preferred = 22)
        ).isNull()
        assertThat(FormatPicker.pickVideo(emptyList(), preferred = 22)).isNull()
    }
}
