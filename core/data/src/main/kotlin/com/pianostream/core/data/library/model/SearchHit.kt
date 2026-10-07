package com.pianostream.core.data.library.model

/**
 * One library-search hit (the `track_fts` FTS4 table over
 * title/performer/composer, queried only through `LibrarySearchDao`, whose
 * MATCH arguments are phrase-quoted with `"` doubling, stripped of `*^−:`,
 * length-capped, and fall back to `LIKE` on syntax error — F16).
 */
public data class SearchHit(
    val trackId: Long,
    val discId: Long,
    val title: String,
    val snippet: String?,
)
