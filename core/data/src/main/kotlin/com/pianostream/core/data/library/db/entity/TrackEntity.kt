package com.pianostream.core.data.library.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `tracks` row (library.db v1). Windows exclude pregap: `endMs` is INDEX 01
 * of the next track (or INDEX 00 when present / FLAC duration for the last)
 * with `endMs > startMs && endMs <= flacDurationMs` enforced at write time
 * (CC11). `pregapStartMs` is the INDEX 00 offset when present — track 1's
 * lead-in lands here, never stretched backwards (H7).
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = DiscEntity::class,
            parentColumns = ["id"],
            childColumns = ["discId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["discId", "trackNo"], unique = true),
        Index(value = ["discId"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val discId: Long,
    val trackNo: Int,
    val title: String,
    val performer: String?,
    val composer: String?,
    val startMs: Long,
    val endMs: Long?,
    val pregapStartMs: Long?,
    val gainDb: Double?,
    val peak: Double?,
    val isrc: String?,
    val flags: Int,
)
