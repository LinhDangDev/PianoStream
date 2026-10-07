package com.pianostream.core.data.library.model

/**
 * Live scan progress reported while the Phase-3 `LibraryScanner` walks the
 * granted SAF tree (disc folders at depth 1 and 2 only — M10 content-driven
 * descent; depth-3 score/PDF trees excluded).
 */
public data class ScanProgress(
    val discsScanned: Int,
    val currentFolder: String?,
)
