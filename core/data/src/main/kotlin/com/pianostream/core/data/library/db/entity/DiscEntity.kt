package com.pianostream.core.data.library.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `discs` row (library.db v1). `scanComplete` is the M2 durability marker —
 * an incomplete disc always re-parses regardless of timestamps.
 * `contentKeyPrefix` is the stable disc hash (folderPath + trackIndexInCue
 * seeds each track's key via [com.pianostream.core.local.scan.LibraryHasher]).
 */
@Entity(
    tableName = "discs",
    indices = [Index(value = ["docId"], unique = true)],
)
data class DiscEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val scanComplete: Boolean = false,
)
