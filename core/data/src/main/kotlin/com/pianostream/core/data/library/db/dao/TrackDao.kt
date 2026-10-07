package com.pianostream.core.data.library.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pianostream.core.data.library.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE discId = :discId ORDER BY trackNo")
    fun observeByDisc(discId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE discId = :discId ORDER BY trackNo")
    suspend fun byDisc(discId: Long): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE discId = :discId")
    suspend fun deleteByDisc(discId: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    /** LIKE fallback used when FTS sanitize refuses or MATCH errors (F16). */
    @Query(
        "SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR " +
            "performer LIKE '%' || :q || '%' OR composer LIKE '%' || :q || '%' " +
            "ORDER BY trackNo",
    )
    suspend fun likeSearch(q: String): List<TrackEntity>

    @Query(
        "SELECT DISTINCT composer FROM tracks WHERE composer IS NOT NULL AND composer != '' " +
            "ORDER BY composer",
    )
    suspend fun distinctComposers(): List<String>
}
