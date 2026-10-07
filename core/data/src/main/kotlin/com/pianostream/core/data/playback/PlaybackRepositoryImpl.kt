package com.pianostream.core.data.playback

import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.dao.PlayHistoryDao
import com.pianostream.core.data.playback.db.entity.FavoriteEntity
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import com.pianostream.core.data.playback.db.entity.QueueStateEntity
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Seam for `clearStreamCache()` — core:data cannot see `MediaCache`
 * (dependency direction); a concrete fun interface avoids the
 * wildcard-mismatch problems of raw `suspend () -> Unit` injection.
 */
public fun interface StreamCacheEvictor {
    public suspend fun evict()
}

/** [PlaybackRepository] backed by Room `playback.db` (Phase 2). */
@Singleton
class PlaybackRepositoryImpl @Inject constructor(
    private val db: PlaybackDatabase,
    private val streamCacheEvictor: StreamCacheEvictor = StreamCacheEvictor {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PlaybackRepository {

    private val queueDao = db.queueDao()
    private val historyDao = db.playHistoryDao()
    private val favoriteDao = db.favoriteDao()
    private val maintenanceDao = db.maintenanceDao()

    // --- queue ----------------------------------------------------------

    override fun observeQueue(): Flow<List<QueueItem>> =
        queueDao.observeQueue().map { rows -> rows.map(::toQueueItem) }

    override fun observeQueueState(): Flow<QueueState?> =
        queueDao.observeQueueState().map { it?.let(::toQueueState) }

    override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {
        // H6: one transaction, fresh addedAt stamped here.
        val addedAt = nowMs()
        queueDao.replaceQueue(
            items.mapIndexed { index, item ->
                QueueItemEntity(
                    position = index,
                    mediaId = item.mediaId,
                    title = item.title,
                    artist = item.artist,
                    artworkUri = item.artworkUri,
                    source = item.source.name,
                    durationMs = item.durationMs,
                    addedAt = addedAt,
                    missing = item.missing,
                )
            },
            state?.let(::toQueueStateEntity),
        )
    }

    override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {
        queueDao.moveItem(fromPosition, toPosition)
    }

    override suspend fun saveQueueCursor(state: QueueState) {
        queueDao.saveQueueState(toQueueStateEntity(state.copy(updatedAt = nowMs())))
    }

    /** Used by the session's cold-start restore (M9). */
    suspend fun hasSavedQueue(): Boolean =
        queueDao.queueSnapshot().isNotEmpty()

    suspend fun queueSnapshot(): List<QueueItem> =
        queueDao.queueSnapshot().map(::toQueueItem)

    suspend fun queueCursor(): QueueState? =
        queueDao.queueState()?.let(::toQueueState)

    // --- history ----------------------------------------------------------

    override fun observeHistory(): Flow<List<PlayHistoryItem>> =
        historyDao.observeHistory().map { rows -> rows.map(::toHistoryItem) }

    override suspend fun appendHistory(item: PlayHistoryItem) {
        historyDao.insertAndPrune(
            PlayHistoryEntity(
                mediaId = item.mediaId,
                title = item.title,
                artist = item.artist,
                artworkUri = item.artworkUri,
                source = item.source.name,
                playedAt = item.playedAt,
                playedMs = item.playedMs,
                missing = item.missing,
            ),
            maxRows = PlayHistoryDao.MAX_ROWS,
            oldestKept = nowMs() - PlayHistoryDao.MAX_AGE_MS,
        )
    }

    // --- favorites ----------------------------------------------------------

    override fun observeFavorites(): Flow<List<FavoriteItem>> =
        favoriteDao.observeFavorites().map { rows -> rows.map(::toFavorite) }

    override suspend fun addFavorite(item: FavoriteItem) {
        favoriteDao.upsert(
            FavoriteEntity(
                mediaId = item.mediaId,
                title = item.title,
                artist = item.artist,
                artworkUri = item.artworkUri,
                source = item.source.name,
                addedAt = item.addedAt,
                missing = item.missing,
            )
        )
    }

    override suspend fun removeFavorite(mediaId: String) = favoriteDao.delete(mediaId)

    override suspend fun isFavorite(mediaId: String): Boolean = favoriteDao.exists(mediaId)

    // --- reconcile / wipe -------------------------------------------------

    override suspend fun reconcileMissing(existingMediaIds: Set<String>) {
        maintenanceDao.reconcile(existingMediaIds.toList())
    }

    override suspend fun clearStreamCache() = streamCacheEvictor.evict()

    override suspend fun clearAll() {
        queueDao.deleteAllItems()
        queueDao.deleteQueueState()
        historyDao.deleteAll()
        favoriteDao.deleteAll()
        // Room closes/deletes the physical file (-wal/-shm) via the caller's
        // db.close() + File.delete — the rows are gone either way (CC10).
    }

    // --- mapping ----------------------------------------------------------

    private fun toQueueItem(e: QueueItemEntity) = QueueItem(
        id = e.id,
        position = e.position,
        mediaId = e.mediaId,
        title = e.title,
        artist = e.artist,
        artworkUri = e.artworkUri,
        source = MediaSource.valueOf(e.source),
        durationMs = e.durationMs,
        addedAt = e.addedAt,
        missing = e.missing,
    )

    private fun toQueueState(e: QueueStateEntity) = QueueState(
        currentIndex = e.currentIndex,
        positionMs = e.positionMs,
        repeatMode = e.repeatMode,
        shuffle = e.shuffle,
        updatedAt = e.updatedAt,
    )

    private fun toQueueStateEntity(s: QueueState) = QueueStateEntity(
        currentIndex = s.currentIndex,
        positionMs = s.positionMs,
        repeatMode = s.repeatMode,
        shuffle = s.shuffle,
        updatedAt = s.updatedAt,
    )

    private fun toHistoryItem(e: PlayHistoryEntity) = PlayHistoryItem(
        id = e.id,
        mediaId = e.mediaId,
        title = e.title,
        artist = e.artist,
        artworkUri = e.artworkUri,
        source = MediaSource.valueOf(e.source),
        playedAt = e.playedAt,
        playedMs = e.playedMs,
        missing = e.missing,
    )

    private fun toFavorite(e: FavoriteEntity) = FavoriteItem(
        mediaId = e.mediaId,
        title = e.title,
        artist = e.artist,
        artworkUri = e.artworkUri,
        source = MediaSource.valueOf(e.source),
        addedAt = e.addedAt,
        missing = e.missing,
    )
}
