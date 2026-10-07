package com.pianostream.core.local.api

/**
 * One local track's playback window inside its disc FLAC (Interface Freeze —
 * CC1, CC11, M3).
 *
 * `flacUri` is a SAF `content://` document URI resolved through `discs`/`tracks`
 * rows — never the untrusted path carried inside a `pianostream://local/` URI
 * (CC9). Windows **exclude** pregap: `startMs`/`endMs` are the INDEX 01 bounds.
 *
 * `endMs` is `Long?` — `null` when the FLAC duration is unknown; the
 * `Long.MAX_VALUE` sentinel is gone (M3) and `endMs == null` ⇒ the
 * `ClippingConfiguration` simply omits `setEndPositionMs`. When non-null,
 * `endMs > startMs && endMs <= flacDurationMs` is required (CC11) and enforced
 * at construction.
 *
 * `gainDb`/`peak` are the per-track ReplayGain pair (null when the cue sheet
 * carries none); `:core:media` stamps them into `MediaMetadata.extras` for the
 * ReplayGainAudioProcessor (H2/H3).
 */
public data class LocalTrackWindow(
    val contentKey: String,
    val flacUri: String,
    val startMs: Long,
    val endMs: Long?,
    val gainDb: Double?,
    val peak: Double?,
) {
    init {
        require(startMs >= 0) { "startMs must be >= 0" }
        require(endMs == null || endMs > startMs) { "endMs must be > startMs (CC11)" }
    }
}
