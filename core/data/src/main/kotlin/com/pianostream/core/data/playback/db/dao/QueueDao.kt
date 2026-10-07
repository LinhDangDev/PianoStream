package com.pianostream.core.data.playback.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import com.pianostream.core.data.playback.db.entity.QueueStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Queue rows + the `queue_state` cursor. Every multi-row rewrite runs inside a
 * single `@Transaction` (H6) — a partial queue is never observable.
 */
@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    fun observeQueue(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun queueSnapshot(): List<QueueItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_items")
    suspend fun deleteAllItems()

    @Query("UPDATE queue_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    // --- queue_state --------------------------------------------------------

    @Query("SELECT * FROM queue_state WHERE id = 1")
    fun observeQueueState(): Flow<QueueStateEntity?>

    @Query("SELECT * FROM queue_state WHERE id = 1")
    suspend fun queueState(): QueueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQueueState(state: QueueStateEntity)

    @Query("DELETE FROM queue_state")
    suspend fun deleteQueueState()

    // --- transactional compound ops ----------------------------------------

    /**
     * Full-queue replacement in ONE transaction (H6): wipe + reinsert with the
     * caller's fresh `addedAt` values, then store the cursor — an observer
     * never sees a half-written queue.
     */
    @Transaction
    suspend fun replaceQueue(items: List<QueueItemEntity>, state: QueueStateEntity?) {
        deleteAllItems()
        if (items.isNotEmpty()) insertAll(items)
        if (state != null) saveQueueState(state) else deleteQueueState()
    }

    /**
     * Per-track move (CC1): renumbers `position` for exactly the affected
     * range inside one transaction.
     */
    @Transaction
    suspend fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val rows = queueSnapshot().toMutableList()
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return
        val moved = rows.removeAt(fromPosition)
        rows.add(toPosition, moved)
        deleteAllItems()
        insertAll(rows.mapIndexed { index, row -> row.copy(position = index, id = 0) })
    }
}
