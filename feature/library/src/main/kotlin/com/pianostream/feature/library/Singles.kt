package com.pianostream.feature.library

import com.pianostream.core.data.library.model.Disc

/**
 * Shared contract with the scan pipeline: a loose audio file that no .cue
 * FILE entry references is stored as a one-track disc, marked without a
 * schema change via an empty `cueUri` (null or the "" sentinel) — a scanned
 * cue disc always carries its .cue file's content URI there.
 */
internal fun Disc.isSingle(): Boolean = cueUri.isNullOrEmpty()
