package com.pianostream.core.data.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.entity.PlaylistEntity
import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Merge into…" math — [PlaybackRepositoryImpl.mergePlaylist] against an
 * in-memory `playback.db` (Robolectric): source tracks append to the
 * target's tail preserving order, positions continue from the target's
 * current max, mediaId dupes are skipped, and the source playlist is left
 * untouched.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistMergeTest {

    private lateinit var db: PlaybackDatabase
    private lateinit var repo: PlaybackRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlaybackDatabase::class.java,
        ).build()
        repo = PlaybackRepositoryImpl(db)
    }

    @After
    fun close() = db.close()

    private fun playlistItem(playlistId: Long, mediaId: String, position: Int) =
        PlaylistItemEntity(
            playlistId = playlistId,
            mediaId = mediaId,
            position = position,
            title = "t-$mediaId",
            artist = null,
            artworkUri = null,
            source = "LOCAL",
            addedAt = 1,
        )

    private suspend fun playlist(name: String): Long =
        db.playlistDao().insert(PlaylistEntity(name = name, createdAt = 1))

    private suspend fun ids(playlistId: Long): List<String> =
        db.playlistItemDao().snapshot(playlistId).map { it.mediaId }

    @Test
    fun merge_appends_in_order_positions_continue_dupes_skipped() = runBlocking {
        val target = playlist("B")
        val source = playlist("A")
        val items = db.playlistItemDao()
        items.upsert(playlistItem(target, "m1", 0))
        items.upsert(playlistItem(target, "m2", 1))
        // m2 already sits in target → skipped; s3/s4 append after position 1.
        items.upsert(playlistItem(source, "s3", 0))
        items.upsert(playlistItem(source, "m2", 1))
        items.upsert(playlistItem(source, "s4", 2))

        val result = repo.mergePlaylist(sourceId = source, targetId = target)

        assertThat(result.added).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(1)
        val merged = items.snapshot(target)
        assertThat(merged.map { it.mediaId })
            .containsExactly("m1", "m2", "s3", "s4").inOrder()
        assertThat(merged.map { it.position })
            .containsExactly(0, 1, 2, 3).inOrder()
        // Source playlist keeps its own rows — merge never deletes.
        assertThat(ids(source)).containsExactly("s3", "m2", "s4").inOrder()
    }

    @Test
    fun merge_into_empty_target_starts_at_zero() = runBlocking {
        val target = playlist("Empty")
        val source = playlist("A")
        val items = db.playlistItemDao()
        items.upsert(playlistItem(source, "s1", 0))
        items.upsert(playlistItem(source, "s2", 1))

        val result = repo.mergePlaylist(sourceId = source, targetId = target)

        assertThat(result.added).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(0)
        val merged = items.snapshot(target)
        assertThat(merged.map { it.position }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun duplicate_mediaId_inside_source_counts_once() = runBlocking {
        val target = playlist("B")
        val source = playlist("A")
        val items = db.playlistItemDao()
        // The (playlistId, mediaId) PK makes real dupes impossible in DB, but
        // the planner is fed snapshots — feed it one directly to pin the rule.
        val plan = PlaylistMerge.plan(
            targetPlaylistId = target,
            target = listOf(playlistItem(target, "m1", 0)),
            source = listOf(
                playlistItem(source, "s1", 0),
                playlistItem(source, "s1", 1),
                playlistItem(source, "m1", 2),
            ),
        )
        assertThat(plan.inserts.map { it.mediaId }).containsExactly("s1")
        assertThat(plan.inserts.single().position).isEqualTo(1)
        assertThat(plan.skipped).isEqualTo(2)
    }

    @Test
    fun merging_a_playlist_into_itself_is_a_noop() = runBlocking {
        val id = playlist("Solo")
        db.playlistItemDao().upsert(playlistItem(id, "m1", 0))

        val result = repo.mergePlaylist(sourceId = id, targetId = id)

        assertThat(result.added).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(ids(id)).containsExactly("m1").inOrder()
    }

    @Test
    fun target_track_count_and_cover_refresh() = runBlocking {
        val target = playlist("B")
        val source = playlist("A")
        val items = db.playlistItemDao()
        items.upsert(playlistItem(source, "s1", 0).copy(artworkUri = "content://art/1"))
        items.upsert(playlistItem(source, "s2", 1))

        repo.mergePlaylist(sourceId = source, targetId = target)

        val row = db.playlistDao().observeWithCounts().first()
            .single { it.playlist.id == target }
        assertThat(row.trackCount).isEqualTo(2)
        // refreshCover picks the first tracks' art (the merged-in rows).
        assertThat(row.playlist.coverUris).isEqualTo("content://art/1")
    }
}
