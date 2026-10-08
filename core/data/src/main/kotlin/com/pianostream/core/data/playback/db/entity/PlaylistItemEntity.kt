package com.pianostream.core.data.playback.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * `playlist_items` — one track inside a playlist, keyed by
 * `(playlistId, mediaId)` so a track appears at most once per playlist
 * (re-adding moves it to the end). Denormalized display columns per CC6;
 * deleting the playlist cascades its rows.
 */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val mediaId: String,
    val position: Int,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val source: String,
    val addedAt: Long,
)
