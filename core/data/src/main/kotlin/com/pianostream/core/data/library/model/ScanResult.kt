package com.pianostream.core.data.library.model

/**
 * Terminal result of a library scan. `missingCount` carries the B1 reconcile
 * count (playback rows tombstoned because their mediaId no longer resolves).
 * `warnings` collect non-fatal per-disc issues (oversize cue/log > 1 MiB,
 * unreadable FILE siblings, unparseable sheets) — scanning is never aborted by
 * a single bad disc; a lost SAF grant aborts with `grantLost` (M2).
 */
public data class ScanResult(
    val discCount: Int,
    val trackCount: Int,
    val durationMs: Long,
    val missingCount: Int,
    val warnings: List<String> = emptyList(),
    val grantLost: Boolean = false,
)
