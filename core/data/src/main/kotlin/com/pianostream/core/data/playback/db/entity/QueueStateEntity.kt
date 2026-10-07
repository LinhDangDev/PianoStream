package com.pianostream.core.data.playback.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `queue_state` — the durable queue cursor, always row id=1 (H6). Index and
 * position operate on **tracks** (CC1).
 */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 1,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    val updatedAt: Long,
)
