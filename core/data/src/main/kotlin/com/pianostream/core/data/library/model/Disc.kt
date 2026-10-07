package com.pianostream.core.data.library.model

/**
 * One scanned disc (`discs` row, frozen schema v1).
 *
 * `docId` is the stable SAF document id inside the granted tree;
 * `folderPath` + `discNumber` feed the `contentKey` hash
 * (stable hash of `(folderPath, discRelativeIndex)` — never a Room rowid, B3).
 * `composer` is the distinct aggregate of the disc's tracks' `SONGWRITER`
 * values (CC7 — `SONGWRITER` is a top-level command, never `REM`; `GENRE` is
 * never a composer source).
 *
 * URIs are SAF `content://` document URIs read through the persisted tree grant
 * (F8 — read-only). `artworkUri` may be null; artwork bytes for lockscreen use
 * are cached under `filesDir/artwork/` (E3) — `file://` is never stored here
 * (SEC: no `file://` artwork).
 */
public data class Disc(
    val id: Long,
    val docId: String,
    val treeUri: String,
    val folderPath: String,
    val title: String,
    val performer: String?,
    val composer: String?,
    val genre: String?,
    val date: String?,
    val discId: String?,
    val discNumber: Int?,
    val totalDiscs: Int?,
    val cueUri: String?,
    val flacUri: String,
    val logUri: String?,
    val artworkUri: String?,
    val albumGainDb: Double?,
    val albumPeak: Double?,
    val flacDurationMs: Long?,
    val flacSizeBytes: Long,
    val flacLastModified: Long?,
    val scannedAt: Long,
)
