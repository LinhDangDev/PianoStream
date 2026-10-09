package com.pianostream.core.data.downloads

import kotlinx.coroutines.flow.Flow

/**
 * `downloads` table owner (playback.db v4) — the record store behind
 * `core:media`'s DownloadManager. Rows survive process death; the manager
 * owns file writes and drives every state transition through [upsert].
 */
public interface DownloadRepository {

    /** Every download record, newest first. Emits on every change. */
    public fun observeAll(): Flow<List<DownloadItem>>

    public fun observe(videoId: String): Flow<DownloadItem?>

    public suspend fun get(videoId: String): DownloadItem?

    public suspend fun upsert(item: DownloadItem)

    /** Drops the record — the caller deletes the file itself. */
    public suspend fun delete(videoId: String)

    /** Drops every record (Settings "Clear all downloads" / CC10 wipe). */
    public suspend fun clear()

    /** Marks every QUEUED/DOWNLOADING row FAILED — boot-time recovery. */
    public suspend fun failActive(error: String)
}
