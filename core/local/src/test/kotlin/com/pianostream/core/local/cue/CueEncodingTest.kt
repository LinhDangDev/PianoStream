package com.pianostream.core.local.cue

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class CueEncodingTest {

    private fun fixture(name: String): ByteArray {
        val path = "src/test/resources/cue/$name"
        val f = File(path)
        if (!f.exists()) throw FileNotFoundException(path)
        return f.readBytes()
    }

    @Test
    fun utf8_bom_stripped_and_crlf_tolerated() {
        val sheet = CueParser.parse(fixture("crlf_bom.cue"))
        assertThat(sheet.fileName).isEqualTo("crlf.flac")
        assertThat(sheet.tracks).hasSize(2)
        assertThat(sheet.tracks[0].songwriter).isEqualTo("Comp E")
    }

    @Test
    fun cp1252_fallback_preserves_accents() {
        val sheet = CueParser.parse(fixture("non_ascii.cue"))
        // file is CP1252-encoded (é/ü as raw bytes — invalid UTF-8)
        assertThat(sheet.title).contains("Frédéric")
        assertThat(sheet.tracks[0].songwriter).isEqualTo("Frédéric Chopin")
    }

    @Test
    fun decode_strips_bom() {
        val decoded = CueParser.decode(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "A".toByteArray(),
        )
        assertThat(decoded).isEqualTo("A")
    }
}
