package com.pianostream.core.data.playback.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * `play_history` — capped at [MAX_ROWS] rows / [MAX_AGE_MS] days, pruned on
 * every insert inside the same transaction (F10/F19, CC10).
 */
@Dao
interface PlayHistoryDao {

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    fun observeHistory(): Flow<List<PlayHistoryEntity>>

    @Insert
    suspend fun insert(item: PlayHistoryEntity): Long

    @Query(
        "DELETE FROM play_history WHERE id NOT IN " +
            "(SELECT id FROM play_history ORDER BY playedAt DESC LIMIT :maxRows)",
    )
    suspend fun pruneToMaxRows(maxRows: Int)

    @Query("DELETE FROM play_history WHERE playedAt < :oldestKept")
    suspend fun pruneOlderThan(oldestKept: Long)

    @Query("DELETE FROM play_history")
    suspend fun deleteAll()

    @Transaction
    suspend fun insertAndPrune(item: PlayHistoryEntity, maxRows: Int, oldestKept: Long) {
        insert(item)
        pruneOlderThan(oldestKept)
        pruneToMaxRows(maxRows)
    }

    companion object {
        const val MAX_ROWS = 500
        const val MAX_AGE_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
