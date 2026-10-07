package com.pianostream.core.local.scan

/**
 * Scan traversal constants + pure descent decisions (M10/C2):
 * disc folders live at depth 1–2 under the picked root; descent is
 * content-driven — any folder containing a `.cue` is a disc (stop
 * descending); `pdf|exe|inf|txt` is pruned before stats; absolute sanity
 * bound of 8 levels below the picked root.
 */
object ScanPlan {
    const val MAX_DEPTH = 8
    val PRUNED_EXTENSIONS = setOf("pdf", "exe", "inf", "txt")
    const val MAX_READ_BYTES = 1L * 1024 * 1024 // F20: .cue/.log read guard
    const val REPORT_EVERY = 10

    fun isPruned(displayName: String): Boolean =
        displayName.substringAfterLast('.', "").lowercase() in PRUNED_EXTENSIONS

    /** Disc folders hold ≥1 `.cue` — descend no further inside them. */
    fun isDiscFolder(hasCue: Boolean): Boolean = hasCue

    fun canDescend(depthBelowRoot: Int): Boolean = depthBelowRoot < MAX_DEPTH
}
