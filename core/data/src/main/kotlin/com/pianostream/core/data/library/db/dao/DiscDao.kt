package com.pianostream.core.data.library.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pianostream.core.data.library.db.entity.DiscEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscDao {

    @Query("SELECT * FROM discs ORDER BY discNumber, folderPath")
    fun observeAll(): Flow<List<DiscEntity>>

    @Query("SELECT * FROM discs ORDER BY discNumber, folderPath")
    suspend fun all(): List<DiscEntity>

    @Query("SELECT * FROM discs WHERE id = :id")
    suspend fun byId(id: Long): DiscEntity?

    @Query("SELECT * FROM discs WHERE docId = :docId")
    suspend fun byDocId(docId: String): DiscEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(disc: DiscEntity): Long

    @Query("DELETE FROM discs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM discs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM discs WHERE scanComplete = 1")
    suspend fun completeCount(): Int

    @Query("UPDATE discs SET scanComplete = :complete WHERE id = :id")
    suspend fun setScanComplete(id: Long, complete: Boolean)

    @Query("DELETE FROM discs")
    suspend fun deleteAll()

    /**
     * Discs credited to [artist] — the disc's PERFORMER tag, or at least one
     * of its tracks matching the effective-artist/composer rule in
     * `TrackDao.observeArtistTracks` (includes "appears on" discs; the row
     * subtitle keeps showing the disc's own credit line).
     */
    @Query(
        "SELECT DISTINCT d.* FROM discs d WHERE d.performer = :artist OR EXISTS (" +
            "SELECT 1 FROM tracks t WHERE t.discId = d.id AND (" +
            "COALESCE(NULLIF(t.performer, ''), d.performer, t.composer) = :artist " +
            "OR t.composer = :artist)) " +
            "ORDER BY d.discNumber, d.folderPath",
    )
    fun observeArtistDiscs(artist: String): Flow<List<DiscEntity>>
}
