package com.pianostream.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Contract tests for the sleep-timer sheet's pure (non-Compose) helpers. */
class SleepTimerSheetTest {

    @Test
    fun modes_coverSpecOrderAndCount() {
        assertThat(SleepTimerUi.Mode.entries.map { it.name }).containsExactly(
            "MIN5", "MIN10", "MIN15", "MIN30", "MIN45", "MIN60", "END_OF_TRACK",
        ).inOrder()
    }

    @Test
    fun mode_durationsMatchChips() {
        assertThat(SleepTimerUi.Mode.MIN5.durationMs).isEqualTo(5 * 60_000L)
        assertThat(SleepTimerUi.Mode.MIN10.durationMs).isEqualTo(10 * 60_000L)
        assertThat(SleepTimerUi.Mode.MIN15.durationMs).isEqualTo(15 * 60_000L)
        assertThat(SleepTimerUi.Mode.MIN30.durationMs).isEqualTo(30 * 60_000L)
        assertThat(SleepTimerUi.Mode.MIN45.durationMs).isEqualTo(45 * 60_000L)
        assertThat(SleepTimerUi.Mode.MIN60.durationMs).isEqualTo(60 * 60_000L)
        assertThat(SleepTimerUi.Mode.END_OF_TRACK.durationMs).isNull()
    }

    @Test
    fun chipLabels() {
        assertThat(SleepTimerUi.Mode.MIN5.chipLabel).isEqualTo("5 min")
        assertThat(SleepTimerUi.Mode.MIN60.chipLabel).isEqualTo("60 min")
        assertThat(SleepTimerUi.Mode.END_OF_TRACK.chipLabel).isEqualTo("End of track")
    }

    @Test
    fun countdownText_mmss() {
        assertThat(sleepCountdownText(29 * 60_000L + 58_000L)).isEqualTo("Sleeping in 29:58")
        assertThat(sleepCountdownText(60 * 60_000L)).isEqualTo("Sleeping in 60:00")
        assertThat(sleepCountdownText(0L)).isEqualTo("Sleeping in 0:00")
    }

    @Test
    fun countdownText_floorsPartialSeconds() {
        // 29.9s remaining still reads 0:29 — never rounds up a phantom second.
        assertThat(sleepCountdownText(29_999L)).isEqualTo("Sleeping in 0:29")
    }
}
