package com.pianostream.core.data.playback.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `playlists` — one user playlist. Items live in `playlist_items`.
 * `coverUris` caches the generated mosaic (up to 4 artwork URIs from the
 * first tracks, newline-joined) — written by the repository on every
 * membership change, never by hand (Edit details covers name/description
 * only; the cover note tells the user the art is automatic).
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "") val description: String = "",
    @ColumnInfo(defaultValue = "") val coverUris: String = "",
)
