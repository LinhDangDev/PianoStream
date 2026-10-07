package com.pianostream.core.data.playback.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `play_history` — capped at 500 rows / 90 days, pruned on insert (F10/F19,
 * CC10). Denormalized display columns per CC6; `missing` tombstone per B1.
 */
@Entity(
    tableName = "play_history",
    indices = [Index(value = ["playedAt"])],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: String,
    val playedAt: Long,
    val playedMs: Long,
    val missing: Boolean = false,
)
