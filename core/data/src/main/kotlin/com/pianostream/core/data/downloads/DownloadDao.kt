package com.pianostream.core.data.downloads

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
public interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    public fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    public fun observe(videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    public suspend fun get(videoId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(item: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    public suspend fun delete(videoId: String)

    @Query("DELETE FROM downloads")
    public suspend fun deleteAll()

    /**
     * Process-death sweep: rows still QUEUED/DOWNLOADING when a new manager
     * boots were abandoned mid-flight — they read as FAILED (retryable),
     * never as done or silently stuck.
     */
    @Query(
        "UPDATE downloads SET state = 'FAILED', error = :error, " +
            "updatedAt = :updatedAt WHERE state IN ('QUEUED', 'DOWNLOADING')",
    )
    public suspend fun failActive(error: String, updatedAt: Long)
}
