package com.pianostream.core.data.playback.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `queue_items` — one row per queued track (CC1: per-track media items, never
 * per-disc sources). Denormalized display columns per CC6; `missing` is the
 * B1 tombstone flag, set by post-scan reconcile only — never deleted.
 */
@Entity(
    tableName = "queue_items",
    indices = [Index(value = ["position"], unique = true)],
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val position: Int,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: String,
    val durationMs: Long?,
    val addedAt: Long,
    val missing: Boolean = false,
)
