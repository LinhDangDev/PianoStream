package com.pianostream.core.data.playback.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pianostream.core.data.playback.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

/** Playlist row joined with its track count for the library list. */
data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
)

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Edit-details write: name + free-text description in one update. */
    @Query("UPDATE playlists SET name = :name, description = :description WHERE id = :id")
    suspend fun updateDetails(id: Long, name: String, description: String)

    /** Generated mosaic cache — newline-joined artwork URIs (≤4). */
    @Query("UPDATE playlists SET coverUris = :coverUris WHERE id = :id")
    suspend fun updateCover(id: Long, coverUris: String)

    /** First tracks' artwork for the generated cover (position order, ≤4). */
    @Query(
        "SELECT artworkUri FROM playlist_items " +
            "WHERE playlistId = :playlistId AND artworkUri IS NOT NULL " +
            "ORDER BY position ASC LIMIT 4",
    )
    suspend fun topArtwork(playlistId: Long): List<String>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT playlists.*, " +
            "(SELECT COUNT(*) FROM playlist_items " +
            "WHERE playlist_items.playlistId = playlists.id) AS trackCount " +
            "FROM playlists ORDER BY createdAt ASC, id ASC",
    )
    fun observeWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("DELETE FROM playlists")
    suspend fun deleteAll()
}
