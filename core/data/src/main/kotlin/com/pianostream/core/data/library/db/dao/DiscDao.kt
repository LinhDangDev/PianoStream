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
}
