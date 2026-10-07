package com.pianostream.core.media.audio

import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import org.junit.Test

/** H3 — `10^(dB/20)`, preamp additive, peak limiter at `min(1, 1/peak)`. */
class GainCalculatorTest {

    private fun expectClose(actual: Float, expected: Float, label: String) {
        assertWithMessageClose(label, actual, expected)
    }

    private fun assertWithMessageClose(label: String, actual: Float, expected: Float) {
        com.google.common.truth.Truth.assertWithMessage(label)
            .that(actual).isWithin(1e-6f).of(expected)
    }

    @Test
    fun unity_gain_null_db() {
        assertThat(GainCalculator.linearGain(null, null, 0.0)).isEqualTo(1.0f)
    }

    @Test
    fun plus_6db_is_2x() {
        val expected = 10.0.pow(6.0 / 20.0).toFloat()
        expectClose(GainCalculator.linearGain(6.0, null, 0.0), expected, "+6 dB")
    }

    @Test
    fun minus_6db_is_half() {
        val expected = 10.0.pow(-6.0 / 20.0).toFloat()
        expectClose(GainCalculator.linearGain(-6.0, null, 0.0), expected, "-6 dB")
    }

    @Test
    fun preamp_adds_to_gain() {
        val expected = 10.0.pow((3.0 + 2.0) / 20.0).toFloat()
        expectClose(GainCalculator.linearGain(3.0, null, 2.0), expected, "preamp")
    }

    @Test
    fun peak_limited_when_over_1() {
        // gain would clip: peak 2.0 → capped at 1/2.
        val base = 10.0.pow(6.0 / 20.0)
        val expected = (base * 0.5).toFloat()
        expectClose(GainCalculator.linearGain(6.0, 2.0, 0.0), expected, "peak=2")
    }

    @Test
    fun peak_at_or_below_1_is_noop() {
        val expected = 10.0.pow(6.0 / 20.0).toFloat()
        expectClose(GainCalculator.linearGain(6.0, 1.0, 0.0), expected, "peak=1")
        expectClose(GainCalculator.linearGain(6.0, 0.5, 0.0), expected, "peak=0.5")
    }

    @Test
    fun loud_track_with_peak_attenuates_below_unity() {
        val g = GainCalculator.linearGain(2.0, 1.6, 0.0)
        assertThat(g).isLessThan(1.0f)
        expectClose(g, (10.0.pow(0.1) / 1.6).toFloat(), "peak=1.6")
    }

    @Test
    fun extreme_gain_still_finite() {
        assertThat(GainCalculator.linearGain(24.0, null, 0.0)).isGreaterThan(1.0f)
        assertThat(GainCalculator.linearGain(-60.0, null, 0.0)).isGreaterThan(0.0f)
    }
}
