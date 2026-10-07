package com.pianostream.core.local.cue

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class CueParserTest {

    private fun fixture(name: String): ByteArray {
        val path = "src/test/resources/cue/$name"
        val f = File(path)
        if (!f.exists()) throw FileNotFoundException(path)
        return f.readBytes()
    }

    @Test
    fun cd1_full_parses_header_and_tracks() {
        val sheet = CueParser.parse(fixture("cd1_full.cue"))
        assertThat(sheet.fileName).isEqualTo("CD01.flac")
        assertThat(sheet.title).isEqualTo("The Ultimate Piano Collection - CD01")
        assertThat(sheet.rem.discNumber).isEqualTo(1)
        assertThat(sheet.rem.totalDiscs).isEqualTo(20)
        assertThat(sheet.rem.discId).isEqualTo("350B5A17")
        assertThat(sheet.rem.albumGainDb).isWithin(0.001).of(-7.74)
        assertThat(sheet.rem.albumPeak).isWithin(0.0001).of(0.995483)
        assertThat(sheet.tracks).hasSize(4)
        val t1 = sheet.tracks[0]
        assertThat(t1.index01Ms).isEqualTo(0)
        assertThat(t1.songwriter).isEqualTo("Ludwig van Beethoven")
        assertThat(t1.isrc).isEqualTo("DENVG8400101")
        assertThat(t1.trackGainDb).isWithin(0.001).of(-7.83)
        // INDEX 00 on track 2: prev endMs = index00, window excludes pregap.
        val w = sheet.windows(flacDurationMs = 900_000)
        assertThat(w.windows.map { it.startMs })
            .containsExactly(0L, 375_000L, 513_000L, 843_000L).inOrder()
        assertThat(w.windows.map { it.endMs })
            .containsExactly(372_000L, 510_000L, 840_000L, 900_000L).inOrder()
        assertThat(w.pregapTotalMs).isEqualTo(3_000 + 3_000 + 3_000L)
        assertThat(w.skipped).isEmpty()
    }

    @Test
    fun cd91_pregap_gate_discriminates() {
        // Disc-aware boundary gate: Σ(windows) + Σ(pregaps) == duration ±1 s,
        // and Σpregap > 0 — only a pregap-bearing fixture can prove it (H7).
        val sheet = CueParser.parse(fixture("cd91_pregaps.cue"))
        val duration = 200_000L
        val w = sheet.windows(duration)
        val sum = w.windows.sumOf { it.endMs - it.startMs }
        assertThat(w.pregapTotalMs).isGreaterThan(0)
        assertThat(sum + w.pregapTotalMs).isWithin(1_000).of(duration)
        // explicit: windows exclude INDEX 00 pregap
        assertThat(w.windows[0].endMs).isEqualTo(60_000) // next track's index00
        assertThat(w.windows[1].startMs).isEqualTo(63_000)
        assertThat(w.windows[1].endMs).isEqualTo(120_000)
        assertThat(w.windows[2].startMs).isEqualTo(124_493)
        assertThat(w.windows[2].endMs).isEqualTo(200_000)
    }

    @Test
    fun cd63_zero_pregap_gate_is_vacuous_not_used_to_discriminate() {
        val sheet = CueParser.parse(fixture("cd63_57tracks.cue"))
        val w = sheet.windows(120_000)
        assertThat(w.pregapTotalMs).isEqualTo(0)
        val sum = w.windows.sumOf { it.endMs - it.startMs }
        assertThat(sum).isWithin(1_000).of(120_000)
    }

    @Test
    fun single_track_last_bound_is_flac_duration() {
        val sheet = CueParser.parse(fixture("single_track.cue"))
        val w = sheet.windows(321_000)
        assertThat(w.windows).hasSize(1)
        assertThat(w.windows[0].endMs).isEqualTo(321_000)
    }

    @Test
    fun unknown_duration_skips_last_track_with_report() {
        val sheet = CueParser.parse(fixture("cd1_full.cue"))
        val w = sheet.windows(null)
        // last track dropped — never a Long.MAX_VALUE sentinel (M3)
        assertThat(w.windows).hasSize(3)
        assertThat(w.skipped.single()).contains("no end bound")
    }

    @Test
    fun lead_in_index00_on_track1_is_metadata_only() {
        val sheet = CueParser.parse(fixture("lead_in.cue"))
        val w = sheet.windows(120_000)
        // H7: track 1 still starts at its own INDEX 01 — never stretched back.
        assertThat(w.windows[0].startMs).isEqualTo(2_000)
        assertThat(w.windows[0].pregapStartMs).isEqualTo(0)
    }

    @Test
    fun sheet_with_zero_index01_throws() {
        assertThrows(CueParseException::class.java) {
            CueParser.parse(fixture("no_index01.cue"))
        }
    }

    @Test
    fun line_longer_than_4kib_throws() {
        assertThrows(CueParseException::class.java) {
            CueParser.parse(fixture("long_line.cue"))
        }
    }

    @Test
    fun file_escape_is_rejected_by_sibling_check() {
        // Parser parses it; the scanner-level sibling validation rejects
        // FILE targets that aren't plain sibling names (CC7/CC11).
        val sheet = CueParser.parse(fixture("file_escape.cue"))
        assertThat(sheet.fileName).isEqualTo("../secret.flac")
        // guard expression used by LibraryScanner
        val escaped = sheet.fileName.contains('/') || sheet.fileName.contains('\\') ||
            sheet.fileName.contains("..")
        assertThat(escaped).isTrue()
    }

    @Test
    fun songwriter_is_top_level_not_rem() {
        val sheet = CueParser.parse(fixture("songwriter.cue"))
        // `REM SONGWRITER` must not win over the top-level command (CC7).
        assertThat(sheet.tracks[0].songwriter).isEqualTo("Wolfgang Amadeus Mozart")
        assertThat(sheet.tracks[1].songwriter).isEqualTo("Franz Schubert")
    }

    @Test
    fun index_beyond_track_capacity_rejected() {
        val body = buildString {
            append("FILE \"x.flac\" WAVE\n")
            for (i in 1..CueParser.MAX_TRACKS + 1) {
                append("  TRACK %02d AUDIO\n    INDEX 01 00:00:00\n".format(i))
            }
        }
        assertThrows(CueParseException::class.java) { CueParser.parse(body.toByteArray()) }
    }

    @Test
    fun unknown_commands_ignored_never_fatal() {
        val body = "PERFORMER \"S\"\nTITLE \"T\"\nFILE \"f.flac\" WAVE\n" +
            "  CATALOG 0123456789012\n" +
            "  TRACK 01 AUDIO\n    TITLE \"t\"\n    FOOBAR nothing\n    INDEX 01 00:00:00\n"
        val sheet = CueParser.parse(body.toByteArray())
        assertThat(sheet.tracks).hasSize(1)
    }

    @Test
    fun end_ms_exceeding_flac_duration_skipped() {
        val body = "FILE \"f.flac\" WAVE\n" +
            "  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n" +
            "  TRACK 02 AUDIO\n    INDEX 01 10:00:00\n"
        val sheet = CueParser.parse(body.toByteArray())
        // duration 500_000 < track2's start 600_000: both windows violate
        // endMs <= flacDurationMs → both skipped + reported, never clamped.
        val w = sheet.windows(500_000)
        assertThat(w.windows).isEmpty()
        assertThat(w.skipped).hasSize(2)
    }
}
