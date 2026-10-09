package com.pianostream.feature.player.lyrics

/**
 * Immutable view-state for [LyricsScreen]. Pure data so the screen composes
 * standalone — the integrator maps `core/media`'s Lyrics model into this and
 * feeds `PlayerViewModel.state.positionMs` as the position argument.
 *
 * Synced lyrics arrive in [lines]; a track with only an unsynced text body
 * arrives in [plainText]. Both null means lyrics are still loading; an empty
 * list / blank text means the track has none.
 */
data class LyricsUiState(
    /** Track title shown in the top bar. */
    val title: String,
    /** Track artist shown under the title. */
    val artist: String,
    /** Synced cue lines, sorted by [Line.atMs]; null when only plain/no data. */
    val lines: List<Line>?,
    /** Unsynced lyrics body; null while loading or when synced lines exist. */
    val plainText: String?,
) {
    /** One synced lyric cue: [atMs] is the playback position where it lights up. */
    data class Line(val atMs: Long, val text: String)
}
