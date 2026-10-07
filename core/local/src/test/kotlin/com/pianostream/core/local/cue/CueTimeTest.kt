package com.pianostream.core.local.cue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CueTimeTest {

    @Test
    fun zero() {
        assertThat(CueTime.parse("00:00:00")).isEqualTo(0L)
    }

    @Test
    fun known_offsets() {
        assertThat(CueTime.parse("03:27:37")).isEqualTo(207_493L)
        assertThat(CueTime.parse("59:59:74")).isEqualTo(3_599_986L)
    }

    @Test
    fun rejects_out_of_range_components() {
        assertThat(CueTime.parse("100:00:00")).isNull() // mm > 99
        assertThat(CueTime.parse("00:60:00")).isNull() // ss > 59
        assertThat(CueTime.parse("00:00:75")).isNull() // ff > 74
    }

    @Test
    fun rejects_malformed() {
        assertThat(CueTime.parse("abc")).isNull()
        assertThat(CueTime.parse("1:2:3:4")).isNull()
        assertThat(CueTime.parse("-1:00:00")).isNull()
        assertThat(CueTime.parse("")).isNull()
    }

    @Test
    fun boundary_values_accepted() {
        assertThat(CueTime.parse("99:59:74")).isNotNull()
    }
}
