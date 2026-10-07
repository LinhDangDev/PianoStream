package com.pianostream.core.data.library.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `cue_sheets_raw` — **hash + parserVersion only**, never raw cue text
 * (CC10/F19). A rebuildable cache: a hash or parserVersion change triggers
 * re-parse from SAF on demand; wiped by `deleteAllLocalData()`.
 */
@Entity(tableName = "cue_sheets_raw")
data class CueSheetRawEntity(
    @PrimaryKey val discId: Long,
    val uri: String,
    val contentHash: String,
    val parserVersion: Int,
    val parsedAt: Long,
)
