package com.pianostream.app.fake

import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Deterministic in-memory `PlaybackRepository` for the `stub` flavor. */
@Singleton
public class FakePlaybackRepository @Inject constructor() : PlaybackRepository {

    private val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val queueState = MutableStateFlow<QueueState?>(null)
    private val history = MutableStateFlow<List<PlayHistoryItem>>(emptyList())
    private val favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())

    /** Test hook: count of clearStreamCache() invocations. */
    public var streamCacheClears: Int = 0
        private set

    override fun observeQueue(): Flow<List<QueueItem>> = queue

    override fun observeQueueState(): Flow<QueueState?> = queueState

    override suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?) {
        queue.value = items.mapIndexed { i, it -> it.copy(position = i) }
        queueState.value = state
    }

    override suspend fun moveQueueItem(fromPosition: Int, toPosition: Int) {
        val current = queue.value.toMutableList()
        if (fromPosition !in current.indices || toPosition !in current.indices) return
        val item = current.removeAt(fromPosition)
        current.add(toPosition, item)
        queue.value = current.mapIndexed { i, it -> it.copy(position = i) }
    }

    override suspend fun saveQueueCursor(state: QueueState) {
        queueState.value = state
    }

    override fun observeHistory(): Flow<List<PlayHistoryItem>> = history

    override suspend fun appendHistory(item: PlayHistoryItem) {
        history.value = (listOf(item) + history.value).take(500)
    }

    override fun observeFavorites(): Flow<List<FavoriteItem>> = favorites

    override suspend fun addFavorite(item: FavoriteItem) {
        favorites.value = favorites.value.filterNot { it.mediaId == item.mediaId } + item
    }

    override suspend fun removeFavorite(mediaId: String) {
        favorites.value = favorites.value.filterNot { it.mediaId == mediaId }
    }

    override suspend fun isFavorite(mediaId: String): Boolean =
        favorites.value.any { it.mediaId == mediaId }

    override suspend fun reconcileMissing(existingMediaIds: Set<String>) {
        queue.value = queue.value.map { it.copy(missing = it.mediaId !in existingMediaIds) }
        history.value = history.value.map { it.copy(missing = it.mediaId !in existingMediaIds) }
        favorites.value = favorites.value.map { it.copy(missing = it.mediaId !in existingMediaIds) }
    }

    override suspend fun clearStreamCache() {
        streamCacheClears++
    }

    override suspend fun clearAll() {
        queue.value = emptyList()
        queueState.value = null
        history.value = emptyList()
        favorites.value = emptyList()
    }

    /** Seed helpers for previews/tests. */
    public fun seedQueue(items: List<QueueItem>) {
        queue.value = items.mapIndexed { i, it -> it.copy(position = i) }
    }

    public fun seedFavorites(items: List<FavoriteItem>) {
        favorites.value = items
    }

    public fun seedHistory(items: List<PlayHistoryItem>) {
        history.value = items
    }
}
