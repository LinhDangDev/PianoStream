package com.pianostream.core.local.cue

/** Fatal cue-sheet failure — a sheet with zero valid `INDEX 01` (F20/CC11). */
class CueParseException(message: String) : Exception(message)

/** `REM` fields the collection actually carries (measured 100/100 cues). */
data class CueRemFields(
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumGainDb: Double? = null,
    val albumPeak: Double? = null,
    val genre: String? = null,
    val date: String? = null,
    val discNumber: Int? = null,
    val discId: String? = null,
    val totalDiscs: Int? = null,
)

/** One `TRACK n AUDIO` block of a cue sheet. */
data class CueTrack(
    /** 0-based position of this TRACK block in the sheet (hash input, B3). */
    val indexInCue: Int,
    /** The `TRACK <n>` number as written. */
    val trackNo: Int,
    val title: String? = null,
    val performer: String? = null,
    /** Top-level `SONGWRITER` command — the composer source (CC7). */
    val songwriter: String? = null,
    /** `INDEX 00` offset = pregap start; `null` when absent. */
    val index00Ms: Long? = null,
    /** `INDEX 01` offset = playback window start. Required. */
    val index01Ms: Long,
    val pregapMs: Long? = null,
    val postgapMs: Long? = null,
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val isrc: String? = null,
    val flags: Int = 0,
) {
    /** Pregap duration implied by `INDEX 00 → INDEX 01` (0 when absent). */
    val pregapDurationMs: Long
        get() = index00Ms?.let { (index01Ms - it).coerceAtLeast(0) } ?: 0L
}

/**
 * A parsed cue sheet — disc-level header + ordered tracks.
 */
data class CueSheet(
    /** `FILE` target name — must resolve as a sibling document (CC7/CC11). */
    val fileName: String,
    val title: String? = null,
    val performer: String? = null,
    val rem: CueRemFields = CueRemFields(),
    val tracks: List<CueTrack> = emptyList(),
) {

    /** One produced playback window (INDEX 01 bounds, pregap excluded). */
    data class Window(
        val indexInCue: Int,
        val trackNo: Int,
        val startMs: Long,
        val endMs: Long,
        /** Track-1 `INDEX 00` lead-in / pregap start when present (H7). */
        val pregapStartMs: Long?,
    )

    data class WindowResult(
        val windows: List<Window>,
        /** Per-track reasons windows were dropped (never a silent skip, M3). */
        val skipped: List<String>,
        /** Σ pregap audio unreachable by design (INDEX 00 → INDEX 01 gaps). */
        val pregapTotalMs: Long,
    )

    /**
     * Expands tracks into INDEX-01 windows (CC11/M3):
     *  - `startMs` = own `INDEX 01`;
     *  - `endMs` of track i = track i+1's `INDEX 00` when present, else its
     *    `INDEX 01`; the pregap belongs to no window;
     *  - last `endMs` = [flacDurationMs] — **no `Long.MAX_VALUE`**; an unknown
     *    duration skips the last track and reports it;
     *  - every emitted window satisfies `endMs > startMs && endMs <=
     *    flacDurationMs`; violations are skipped + reported, never clamped.
     */
    fun windows(flacDurationMs: Long?): WindowResult {
        val out = ArrayList<Window>(tracks.size)
        val skipped = ArrayList<String>()
        var pregapTotal = 0L
        tracks.forEachIndexed { i, t ->
            pregapTotal += t.pregapDurationMs
            val next = tracks.getOrNull(i + 1)
            val end: Long? = when {
                next != null -> next.index00Ms ?: next.index01Ms
                flacDurationMs != null -> flacDurationMs
                else -> null
            }
            when {
                end == null ->
                    skipped += "track ${t.trackNo}: no end bound (unknown FLAC duration)"
                end <= t.index01Ms ->
                    skipped += "track ${t.trackNo}: endMs($end) <= startMs(${t.index01Ms})"
                flacDurationMs != null && end > flacDurationMs ->
                    skipped += "track ${t.trackNo}: endMs($end) > flacDuration($flacDurationMs)"
                else -> out += Window(
                    indexInCue = t.indexInCue,
                    trackNo = t.trackNo,
                    startMs = t.index01Ms,
                    endMs = end,
                    pregapStartMs = t.index00Ms,
                )
            }
        }
        return WindowResult(out, skipped, pregapTotal)
    }
}
