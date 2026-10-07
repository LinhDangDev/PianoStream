package com.pianostream.core.data.playback.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `favorites` — keyed by `mediaId` (`pianostream://…` URI). Denormalized
 * display columns per CC6; `missing` tombstone per B1.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: String,
    val addedAt: Long,
    val missing: Boolean = false,
)
