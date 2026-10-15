package com.pianostream.app.fake

import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.Playlist
import com.pianostream.core.data.playback.model.PlaylistMergeResult
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

    init {
        StubRuntime.playbackRepository = this
    }

    private val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val queueState = MutableStateFlow<QueueState?>(null)
    private val history = MutableStateFlow<List<PlayHistoryItem>>(emptyList())
    private val favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val playlistTracks = MutableStateFlow<Map<Long, List<QueueItem>>>(emptyMap())
    private var nextPlaylistId: Long = 1L

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

    // --- user playlists -----------------------------------------------------

    override fun observePlaylists(): Flow<List<Playlist>> = playlists

    override suspend fun createPlaylist(name: String): Long {
        val id = nextPlaylistId++
        playlists.value = playlists.value + Playlist(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
            trackCount = 0,
        )
        playlistTracks.value = playlistTracks.value + (id to emptyList())
        return id
    }

    override suspend fun renamePlaylist(id: Long, name: String) {
        playlists.value = playlists.value.map {
            if (it.id == id) it.copy(name = name) else it
        }
    }

    override suspend fun deletePlaylist(id: Long) {
        playlists.value = playlists.value.filterNot { it.id == id }
        playlistTracks.value = playlistTracks.value - id
    }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<QueueItem>> =
        playlistTracks.map { it[playlistId].orEmpty() }

    override suspend fun addToPlaylist(playlistId: Long, item: QueueItem) {
        val tracks = playlistTracks.value[playlistId].orEmpty()
        playlistTracks.value = playlistTracks.value +
            (playlistId to tracks + item.copy(position = tracks.size))
        playlists.value = playlists.value.map {
            if (it.id == playlistId) it.copy(trackCount = tracks.size + 1) else it
        }
    }

    override suspend fun removeFromPlaylist(playlistId: Long, mediaId: String) {
        val tracks = playlistTracks.value[playlistId].orEmpty()
            .filterNot { it.mediaId == mediaId }
            .mapIndexed { i, it -> it.copy(position = i) }
        playlistTracks.value = playlistTracks.value + (playlistId to tracks)
        playlists.value = playlists.value.map {
            if (it.id == playlistId) it.copy(trackCount = tracks.size) else it
        }
    }

    override suspend fun mergePlaylist(sourceId: Long, targetId: Long): PlaylistMergeResult {
        if (sourceId == targetId) return PlaylistMergeResult(added = 0, skipped = 0)
        val source = playlistTracks.value[sourceId].orEmpty()
        val target = playlistTracks.value[targetId].orEmpty()
        val existing = target.mapTo(HashSet()) { it.mediaId }
        val merged = target.toMutableList()
        var skipped = 0
        for (item in source) {
            if (existing.add(item.mediaId)) {
                merged += item.copy(position = merged.size)
            } else {
                skipped++
            }
        }
        playlistTracks.value = playlistTracks.value + (targetId to merged)
        playlists.value = playlists.value.map {
            if (it.id == targetId) it.copy(trackCount = merged.size) else it
        }
        return PlaylistMergeResult(added = merged.size - target.size, skipped = skipped)
    }

    override suspend fun clearStreamCache() {
        streamCacheClears++
    }

    override suspend fun clearAll() {
        queue.value = emptyList()
        queueState.value = null
        history.value = emptyList()
        favorites.value = emptyList()
        playlists.value = emptyList()
        playlistTracks.value = emptyMap()
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
