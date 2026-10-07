package com.pianostream.feature.library

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.model.Disc
import org.junit.Test

/**
 * The pipeline marks a loose file (no .cue FILE entry) as a one-track
 * disc with an empty `cueUri` — either null or the "" sentinel. A scanned
 * cue disc always carries its .cue file's content URI there.
 */
class SinglesTest {

    private fun disc(cueUri: String?) = Disc(
        id = 1, docId = "d", treeUri = "t", folderPath = "f",
        title = "T", performer = null, composer = null, genre = null,
        date = null, discId = null, discNumber = null, totalDiscs = null,
        cueUri = cueUri, flacUri = "u", logUri = null, artworkUri = null,
        albumGainDb = null, albumPeak = null, flacDurationMs = null,
        flacSizeBytes = 0, flacLastModified = null, scannedAt = 0L,
    )

    @Test
    fun `cue-backed disc is not a single`() {
        assertThat(disc("content://tree/disc1/cue.cue").isSingle()).isFalse()
    }

    @Test
    fun `null cueUri marks a single`() {
        assertThat(disc(null).isSingle()).isTrue()
    }

    @Test
    fun `empty cueUri sentinel marks a single`() {
        assertThat(disc("").isSingle()).isTrue()
    }
}
