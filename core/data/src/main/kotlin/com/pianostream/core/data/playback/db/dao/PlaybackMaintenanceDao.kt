package com.pianostream.core.data.playback.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PlaybackMaintenanceDao {
    @Query(
        "UPDATE queue_items SET missing = 1 WHERE mediaId LIKE 'pianostream://local/%' " +
            "AND mediaId NOT IN (:existingMediaIds)",
    )
    suspend fun tombstoneQueue(existingMediaIds: List<String>)

    @Query(
        "UPDATE queue_items SET missing = 0 WHERE mediaId IN (:existingMediaIds)",
    )
    suspend fun reviveQueue(existingMediaIds: List<String>)

    @Query("UPDATE queue_items SET missing = 1 WHERE mediaId LIKE 'pianostream://local/%'")
    suspend fun tombstoneAllLocalQueue()
    @Query(
        "UPDATE play_history SET missing = 1 WHERE mediaId LIKE 'pianostream://local/%' " +
            "AND mediaId NOT IN (:existingMediaIds)",
    )
    suspend fun tombstoneHistory(existingMediaIds: List<String>)

    @Query(
        "UPDATE play_history SET missing = 0 WHERE mediaId IN (:existingMediaIds)",
    )
    suspend fun reviveHistory(existingMediaIds: List<String>)

    @Query("UPDATE play_history SET missing = 1 WHERE mediaId LIKE 'pianostream://local/%'")
    suspend fun tombstoneAllLocalHistory()
    @Query(
        "UPDATE favorites SET missing = 1 WHERE mediaId LIKE 'pianostream://local/%' " +
            "AND mediaId NOT IN (:existingMediaIds)",
    )
    suspend fun tombstoneFavorites(existingMediaIds: List<String>)

    @Query(
        "UPDATE favorites SET missing = 0 WHERE mediaId IN (:existingMediaIds)",
    )
    suspend fun reviveFavorites(existingMediaIds: List<String>)

    @Query("UPDATE favorites SET missing = 1 WHERE mediaId LIKE 'pianostream://local/%'")
    suspend fun tombstoneAllLocalFavorites()
    /**
     * One-transaction reconcile across all three tables (B1). When
     * [existingMediaIds] is empty every local row is tombstoned.
     */
    @Transaction
    suspend fun reconcile(existingMediaIds: List<String>) {
        if (existingMediaIds.isEmpty()) {
            tombstoneAllLocalQueue()
            tombstoneAllLocalHistory()
            tombstoneAllLocalFavorites()
            return
        }
        tombstoneQueue(existingMediaIds)
        reviveQueue(existingMediaIds)
        tombstoneHistory(existingMediaIds)
        reviveHistory(existingMediaIds)
        tombstoneFavorites(existingMediaIds)
        reviveFavorites(existingMediaIds)
    }
}



