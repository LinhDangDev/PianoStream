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
