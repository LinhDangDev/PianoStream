package com.pianostream.core.data.playback.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** `playlists` — one user playlist. Items live in `playlist_items`. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)
