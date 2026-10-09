package com.pianostream.core.data.playback.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistItemDao {

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun snapshot(playlistId: Long): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun delete(playlistId: Long, mediaId: String)

    /** -1 when the playlist is empty — callers store `maxPosition + 1`. */
    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("UPDATE playlist_items SET position = :position " +
        "WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun updatePosition(playlistId: Long, mediaId: String, position: Int)

    /** Ids of every playlist containing [mediaId] — "Saved in" membership. */
    @Query("SELECT DISTINCT playlistId FROM playlist_items WHERE mediaId = :mediaId")
    fun observePlaylistsContaining(mediaId: String): Flow<List<Long>>

    @Query("SELECT DISTINCT playlistId FROM playlist_items WHERE mediaId = :mediaId")
    suspend fun playlistIdsContaining(mediaId: String): List<Long>

    /** "Clear all" — drops the track from every playlist at once. */
    @Query("DELETE FROM playlist_items WHERE mediaId = :mediaId")
    suspend fun deleteEverywhere(mediaId: String)

    /**
     * Drag-to-reorder (CC1-style, positions are the PK's ordering column):
     * renumbers `position` for the affected range in one transaction so an
     * observer never sees a half-written order.
     */
    @Transaction
    suspend fun move(playlistId: Long, fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val rows = snapshot(playlistId).toMutableList()
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return
        val moved = rows.removeAt(fromPosition)
        rows.add(toPosition, moved)
        rows.forEachIndexed { index, row ->
            if (row.position != index) {
                updatePosition(playlistId, row.mediaId, index)
            }
        }
    }

    @Query("DELETE FROM playlist_items")
    suspend fun deleteAll()
}
