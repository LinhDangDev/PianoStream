package com.pianostream.core.media.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM parser coverage: stamps, multi-tag lines, offset, malformed, empty. */
class LrcParserTest {

    @Test
    fun single_timed_line() {
        val lines = LrcParser.parse("[00:32.50]hello")
        assertThat(lines).containsExactly(LyricsLine(32_500L, "hello"))
    }

    @Test
    fun fraction_scales_to_ms() {
        assertThat(LrcParser.parse("[00:00.5]a").first().atMs).isEqualTo(500L)
        assertThat(LrcParser.parse("[00:00.05]a").first().atMs).isEqualTo(50L)
        assertThat(LrcParser.parse("[00:00.005]a").first().atMs).isEqualTo(5L)
        assertThat(LrcParser.parse("[00:01.5]a").first().atMs).isEqualTo(1_500L)
        assertThat(LrcParser.parse("[00:01]a").first().atMs).isEqualTo(1_000L)
    }

    @Test
    fun multi_timestamp_line_produces_multiple_entries() {
        val lines = LrcParser.parse("[00:10.00][00:20.00][00:30.00]repeat me")
        assertThat(lines).containsExactly(
            LyricsLine(10_000L, "repeat me"),
            LyricsLine(20_000L, "repeat me"),
            LyricsLine(30_000L, "repeat me"),
        )
    }

    @Test
    fun lines_sorted_by_time_not_file_order() {
        val lines = LrcParser.parse("[00:20.00]later\n[00:05.00]sooner")
        assertThat(lines.map { it.atMs }).containsExactly(5_000L, 20_000L).inOrder()
    }

    @Test
    fun positive_offset_pushes_lyrics_later() {
        val lines = LrcParser.parse("[offset:+500]\n[00:10.00]line")
        assertThat(lines).containsExactly(LyricsLine(10_500L, "line"))
    }

    @Test
    fun negative_offset_pulls_earlier() {
        val lines = LrcParser.parse("[offset:-250]\n[00:10.00]line")
        assertThat(lines).containsExactly(LyricsLine(9_750L, "line"))
    }

    @Test
    fun offset_applies_globally_wherever_the_tag_sits() {
        // Offset tag after the lyric line still shifts it.
        val lines = LrcParser.parse("[00:10.00]line\n[offset:+1000]")
        assertThat(lines).containsExactly(LyricsLine(11_000L, "line"))
    }

    @Test
    fun offset_clamps_timestamps_at_zero() {
        val lines = LrcParser.parse("[offset:-999999]\n[00:10.00]line")
        assertThat(lines).containsExactly(LyricsLine(0L, "line"))
    }

    @Test
    fun blank_lyric_text_is_kept() {
        // Timestamp-only lines are instrumental markers — kept, not dropped.
        val lines = LrcParser.parse("[00:10.00]\n[00:20.00]words")
        assertThat(lines).containsExactly(
            LyricsLine(10_000L, ""),
            LyricsLine(20_000L, "words"),
        )
    }

    @Test
    fun metadata_and_prose_lines_ignored() {
        val lines = LrcParser.parse(
            "[ti:Some Title]\n[ar:Some Artist]\n[length: 03:30]\n" +
                "this line has no tags\n[00:10.00]real lyric",
        )
        assertThat(lines).containsExactly(LyricsLine(10_000L, "real lyric"))
    }

    @Test
    fun malformed_tags_ignored_but_line_survives() {
        val lines = LrcParser.parse(
            "[xx:yy.zz]garbage\n[1:2:3:4]not a stamp\n[00:10.00]good",
        )
        assertThat(lines).containsExactly(LyricsLine(10_000L, "good"))
    }

    @Test
    fun empty_and_blank_input_yield_no_lines() {
        assertThat(LrcParser.parse("")).isEmpty()
        assertThat(LrcParser.parse("   \n \n")).isEmpty()
        assertThat(LrcParser.parse("[ti:only metadata]")).isEmpty()
    }

    @Test
    fun bom_is_tolerated() {
        val lines = LrcParser.parse("\uFEFF[00:10.00]line")
        assertThat(lines).containsExactly(LyricsLine(10_000L, "line"))
    }

    @Test
    fun tolyrics_classifies_synced() {
        val lyrics = LrcParser.toLyrics("[00:10.00]a\n[00:20.00]b")
        assertThat(lyrics).isEqualTo(
            Lyrics.Synced(listOf(LyricsLine(10_000L, "a"), LyricsLine(20_000L, "b"))),
        )
    }

    @Test
    fun tolyrics_classifies_plain_stripped_of_tags() {
        val lyrics = LrcParser.toLyrics("[ti:Title]\nfirst words\nsecond words")
        assertThat(lyrics).isEqualTo(Lyrics.Plain("first words\nsecond words"))
    }

    @Test
    fun tolyrics_blank_yields_null() {
        assertThat(LrcParser.toLyrics("")).isNull()
        assertThat(LrcParser.toLyrics("[ti:only meta]")).isNull()
    }
}
