package com.pianostream.core.data.playback

import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.playback.model.QueueState
import kotlinx.coroutines.flow.Flow

/**
 * `playback.db` owner — queue, queue cursor, history, favorites
 * (Interface Freeze; Phase 2 implements `PlaybackRepositoryImpl` +
 * `playback/db/`**`).
 *
 * Contracts baked in:
 *  - **CC1** — queue rows are per-track media items; `position`/`currentIndex`
 *    are track indices.
 *  - **CC6** — no cross-database joins: every row carries denormalized
 *    `title`/`artist`/`artworkUri` so UI renders without `library.db`.
 *  - **B1** — [reconcileMissing] marks rows whose mediaId no longer resolves
 *    to a live source as `missing=true` tombstones; rows are never silently
 *    deleted.
 *  - **H6** — queue rewrites are `@Transaction`-atomic; [saveQueueCursor] is
 *    driven by Phase 2's 5 s writer + lifecycle saves.
 *  - **F10/F19** — `play_history` is capped at 500 rows / 90 days, pruned on
 *    insert by the implementation.
 *  - **CC10** — [clearAll] is the `playback.db` half of the canonical
 *    `deleteAllLocalData()` wipe.
 */
public interface PlaybackRepository {

    /** Queue rows ordered by `position`. Emits on every change. */
    public fun observeQueue(): Flow<List<QueueItem>>

    /** The durable queue cursor (id=1) or null when never written. */
    public fun observeQueueState(): Flow<QueueState?>

    /** Atomically replaces queue rows + cursor in one transaction (H6). */
    public suspend fun replaceQueue(items: List<QueueItem>, state: QueueState?)

    /** Atomically moves the row at `fromPosition` to `toPosition` (CC1). */
    public suspend fun moveQueueItem(fromPosition: Int, toPosition: Int)

    /** Persists the queue cursor (index + position + repeat/shuffle). */
    public suspend fun saveQueueCursor(state: QueueState)

    /** History newest-first. Capped at 500 rows / 90 days on write. */
    public fun observeHistory(): Flow<List<PlayHistoryItem>>

    /** Appends a history row and prunes the 500-row / 90-day cap. */
    public suspend fun appendHistory(item: PlayHistoryItem)

    public fun observeFavorites(): Flow<List<FavoriteItem>>

    public suspend fun addFavorite(item: FavoriteItem)

    public suspend fun removeFavorite(mediaId: String)

    public suspend fun isFavorite(mediaId: String): Boolean

    /**
     * Post-scan reconcile (B1): sets `missing=true` on every queue/history/
     * favorites row whose `mediaId` is not in [existingMediaIds], and clears the
     * flag on rows that are. Never deletes rows.
     */
    public suspend fun reconcileMissing(existingMediaIds: Set<String>)

    /**
     * Clears the streaming byte cache (the `SimpleCache` under
     * `cacheDir/media3`). Wired to the media cache by the Phase-2 module —
     * the repository owns the *setting action*, not the cache itself.
     */
    public suspend fun clearStreamCache()

    /**
     * Wipes every `playback.db` table — the playback half of the canonical
     * `deleteAllLocalData()` action (CC10; the full wipe is orchestrated by the
     * caller across `library.db`, DataStore, caches and in-memory token
     * singletons).
     */
    public suspend fun clearAll()
}
