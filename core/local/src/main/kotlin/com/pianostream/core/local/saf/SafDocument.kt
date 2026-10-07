package com.pianostream.core.local.saf

/**
 * File kinds the scanner classifies SAF children into (MIME/ext agreement).
 * `.flac` keeps its dedicated [FLAC] kind (cue `FILE` matching keys on it);
 * [AUDIO] covers every other loose audio extension. The scanner treats both
 * kinds as audio candidates for loose-file singles.
 */
enum class SafDocumentKind { CUE, FLAC, AUDIO, LOG, JPEG, FOLDER, OTHER, PRUNED }

/**
 * One row of a `DocumentsContract` children query — `SIZE` doubles as the
 * ≤ 1 MiB read guard (F20) and `lastModified` feeds incremental scanning.
 */
data class SafDocument(
    val docId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val kind: SafDocumentKind,
)
