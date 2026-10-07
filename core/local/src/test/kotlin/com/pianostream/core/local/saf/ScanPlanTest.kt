package com.pianostream.core.local.saf

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.local.scan.ScanPlan
import org.junit.Test

class ScanPlanTest {

    @Test
    fun prunes_non_audio_extensions() {
        assertThat(ScanPlan.isPruned("score.pdf")).isTrue()
        assertThat(ScanPlan.isPruned("setup.EXE")).isTrue()
        assertThat(ScanPlan.isPruned("info.inf")).isTrue()
        assertThat(ScanPlan.isPruned("notes.txt")).isTrue()
        assertThat(ScanPlan.isPruned("CD01.flac")).isFalse()
        assertThat(ScanPlan.isPruned("CD01.cue")).isFalse()
        assertThat(ScanPlan.isPruned("noext")).isFalse()
    }

    @Test
    fun cue_holder_is_disc_folder() {
        assertThat(ScanPlan.isDiscFolder(hasCue = true)).isTrue()
        assertThat(ScanPlan.isDiscFolder(hasCue = false)).isFalse()
    }

    @Test
    fun depth_bound_8_levels() {
        for (d in 0..7) assertThat(ScanPlan.canDescend(d)).isTrue()
        assertThat(ScanPlan.canDescend(8)).isFalse()
        assertThat(ScanPlan.canDescend(99)).isFalse()
    }
}
