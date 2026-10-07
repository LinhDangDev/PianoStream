package com.pianostream.core.data.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `playback.db` v1 schema integrity — runs under Robolectric (the module's
 * `room.testing` dep is a JVM-test artifact, so the schema is exercised
 * through the same SQLite Room generates on-device):
 * tables + `missing` tombstone columns (B1), the unique `position` index on
 * `queue_items`, the `queue_state` singleton, tombstone reconcile and the
 * 500-row/90-day history prune.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackDatabaseMigrationTest {

    private lateinit var db: PlaybackDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlaybackDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun v1_tables_and_missing_columns_exist() {
        val sqlite = db.openHelper.writableDatabase
        for (table in listOf("queue_items", "queue_state", "play_history", "favorites")) {
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { c -> assertThat(c.moveToFirst()).isTrue() }
        }
        // B1 tombstone columns exist on the three data tables (queue_state
        // is a singleton cursor — no per-row tombstone).
        for (table in listOf("queue_items", "play_history", "favorites")) {
            val cols = mutableListOf<String>()
            sqlite.query("PRAGMA table_info($table)").use { c ->
                while (c.moveToNext()) cols += c.getString(1)
            }
            assertThat(cols).contains("missing")
        }
    }

    @Test
    fun queue_position_unique_index_enforced() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO queue_items (position, mediaId, title, artist, artworkUri, source, durationMs, addedAt, missing) " +
                "VALUES (0, 'pianostream://local/k1', 'T', NULL, NULL, 'LOCAL', 60000, 1, 0)",
        )
        var threw = false
        try {
            sqlite.execSQL(
                "INSERT INTO queue_items (position, mediaId, title, artist, artworkUri, source, durationMs, addedAt, missing) " +
                    "VALUES (0, 'pianostream://local/k2', 'T2', NULL, NULL, 'LOCAL', 60000, 1, 0)",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun tombstone_reconcile_marks_and_revives() = runBlocking {
        val maint = db.maintenanceDao()
        val queueDao = db.queueDao()
        queueDao.insertAll(
            listOf(
                QueueItemEntity(
                    position = 0, mediaId = "pianostream://local/gone",
                    title = "gone", artist = null, artworkUri = null,
                    source = "LOCAL", durationMs = 1, addedAt = 1,
                ),
                QueueItemEntity(
                    position = 1, mediaId = "pianostream://local/alive",
                    title = "alive", artist = null, artworkUri = null,
                    source = "LOCAL", durationMs = 1, addedAt = 1,
                ),
                QueueItemEntity(
                    position = 2, mediaId = "pianostream://stream/vid00000000",
                    title = "yt", artist = null, artworkUri = null,
                    source = "YOUTUBE", durationMs = 1, addedAt = 1,
                ),
            ),
        )
        maint.reconcile(listOf("pianostream://local/alive"))
        val snapshot = queueDao.queueSnapshot()
        assertThat(snapshot.single { it.mediaId.endsWith("gone") }.missing).isTrue()
        assertThat(snapshot.single { it.mediaId.endsWith("alive") }.missing).isFalse()
        assertThat(snapshot.single { it.mediaId.contains("/stream/") }.missing).isFalse()
    }

    @Test
    fun empty_reconcile_tombstones_every_local_row() = runBlocking {
        val maint = db.maintenanceDao()
        val queueDao = db.queueDao()
        queueDao.insertAll(
            listOf(
                QueueItemEntity(
                    position = 0, mediaId = "pianostream://local/a",
                    title = "a", artist = null, artworkUri = null,
                    source = "LOCAL", durationMs = 1, addedAt = 1,
                ),
                QueueItemEntity(
                    position = 1, mediaId = "pianostream://stream/vid00000000",
                    title = "yt", artist = null, artworkUri = null,
                    source = "YOUTUBE", durationMs = 1, addedAt = 1,
                ),
            ),
        )
        maint.reconcile(emptyList())
        val snapshot = queueDao.queueSnapshot()
        assertThat(snapshot.single { it.mediaId.endsWith("/a") }.missing).isTrue()
        assertThat(snapshot.single { it.mediaId.contains("/stream/") }.missing).isFalse()
    }

    @Test
    fun history_prunes_to_500_rows() = runBlocking {
        val history = db.playHistoryDao()
        repeat(505) { i ->
            history.insert(
                PlayHistoryEntity(
                    mediaId = "m$i", title = "t", artist = null, artworkUri = null,
                    source = "YOUTUBE", playedAt = i.toLong(), playedMs = 0,
                ),
            )
        }
        history.pruneToMaxRows(500)
        val sqlite = db.openHelper.writableDatabase
        sqlite.query("SELECT COUNT(*) FROM play_history").use { c ->
            c.moveToFirst()
            assertThat(c.getLong(0)).isEqualTo(500)
        }
    }

    @Test
    fun queue_state_cursor_round_trips() = runBlocking {
        val dao = db.queueDao()
        dao.saveQueueState(
            com.pianostream.core.data.playback.db.entity.QueueStateEntity(
                currentIndex = 3, positionMs = 42_000, repeatMode = 1,
                shuffle = true, updatedAt = 9,
            ),
        )
        val back = dao.queueState()!!
        assertThat(back.currentIndex).isEqualTo(3)
        assertThat(back.positionMs).isEqualTo(42_000)
        assertThat(back.shuffle).isTrue()
    }

    @Test
    fun move_item_shifts_exactly_one_track() = runBlocking {
        val dao = db.queueDao()
        dao.insertAll(
            listOf("a", "b", "c", "d").mapIndexed { i, id ->
                QueueItemEntity(
                    position = i, mediaId = "pianostream://local/$id",
                    title = id, artist = null, artworkUri = null,
                    source = "LOCAL", durationMs = 1, addedAt = 1,
                )
            },
        )
        dao.moveItem(fromPosition = 3, toPosition = 0)
        val ids = dao.queueSnapshot().sortedBy { it.position }.map { it.mediaId }
        assertThat(ids).containsExactly(
            "pianostream://local/d", "pianostream://local/a",
            "pianostream://local/b", "pianostream://local/c",
        ).inOrder()
    }
}
