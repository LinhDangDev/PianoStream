package com.pianostream.core.data.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.PlaybackMigrations
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import com.pianostream.core.data.playback.db.entity.PlaylistEntity
import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import kotlinx.coroutines.flow.first
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

    // --- v2: playlists ----------------------------------------------------

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

    @Test
    fun v2_playlist_tables_exist() {
        val sqlite = db.openHelper.writableDatabase
        for (table in listOf("playlists", "playlist_items")) {
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { c -> assertThat(c.moveToFirst()).isTrue() }
        }
    }

    @Test
    fun playlist_crud_and_counts() = runBlocking {
        val playlists = db.playlistDao()
        val items = db.playlistItemDao()
        val a = playlists.insert(PlaylistEntity(name = "A", createdAt = 1))
        val b = playlists.insert(PlaylistEntity(name = "B", createdAt = 2))
        items.upsert(playlistItem(a, "m1", 0))
        items.upsert(playlistItem(a, "m2", 1))

        val observed = playlists.observeWithCounts().first()
        assertThat(observed.map { it.playlist.name }).containsExactly("A", "B").inOrder()
        assertThat(observed.first { it.playlist.id == a }.trackCount).isEqualTo(2)
        assertThat(observed.first { it.playlist.id == b }.trackCount).isEqualTo(0)

        playlists.rename(a, "Renamed")
        assertThat(
            playlists.observeWithCounts().first().first { it.playlist.id == a }.playlist.name,
        ).isEqualTo("Renamed")

        assertThat(items.maxPosition(a)).isEqualTo(1)
        assertThat(items.maxPosition(b)).isEqualTo(-1)
    }

    @Test
    fun playlist_items_ordered_by_position_readd_moves_to_end() = runBlocking {
        val items = db.playlistItemDao()
        val id = db.playlistDao().insert(PlaylistEntity(name = "P", createdAt = 1))
        items.upsert(playlistItem(id, "m1", 0))
        items.upsert(playlistItem(id, "m2", 1))
        items.upsert(playlistItem(id, "m3", 2))
        // Re-adding m1 at the tail (REPLACE on the (playlistId, mediaId) PK).
        items.upsert(playlistItem(id, "m1", items.maxPosition(id) + 1))
        assertThat(items.observeItems(id).first().map { it.mediaId })
            .containsExactly("m2", "m3", "m1").inOrder()

        items.delete(id, "m3")
        assertThat(items.observeItems(id).first().map { it.mediaId })
            .containsExactly("m2", "m1").inOrder()
    }

    @Test
    fun playlist_delete_cascades_items() = runBlocking {
        val playlists = db.playlistDao()
        val items = db.playlistItemDao()
        val id = playlists.insert(PlaylistEntity(name = "P", createdAt = 1))
        items.upsert(playlistItem(id, "m1", 0))
        items.upsert(playlistItem(id, "m2", 1))
        playlists.delete(id)
        assertThat(items.observeItems(id).first()).isEmpty()
        assertThat(playlists.observeWithCounts().first()).isEmpty()
    }

    // --- v3: details, reorder, membership --------------------------------------

    @Test
    fun v3_columns_exist() {
        val sqlite = db.openHelper.writableDatabase
        val playlistCols = mutableListOf<String>()
        sqlite.query("PRAGMA table_info(playlists)").use { c ->
            while (c.moveToNext()) playlistCols += c.getString(1)
        }
        assertThat(playlistCols).containsAtLeast("description", "coverUris")
        val itemCols = mutableListOf<String>()
        sqlite.query("PRAGMA table_info(playlist_items)").use { c ->
            while (c.moveToNext()) itemCols += c.getString(1)
        }
        assertThat(itemCols).contains("durationMs")
    }

    @Test
    fun playlist_details_and_cover_update() = runBlocking {
        val playlists = db.playlistDao()
        val items = db.playlistItemDao()
        val id = playlists.insert(PlaylistEntity(name = "P", createdAt = 1))
        playlists.updateDetails(id, "Renamed", "Slow morning mix")
        items.upsert(
            playlistItem(id, "m1", 0).copy(
                artworkUri = "content://art/1",
                durationMs = 61_000,
            ),
        )
        items.upsert(playlistItem(id, "m2", 1).copy(artworkUri = "content://art/2"))
        playlists.updateCover(id, "content://art/1\ncontent://art/2")

        val row = playlists.observeWithCounts().first().single { it.playlist.id == id }
        assertThat(row.playlist.name).isEqualTo("Renamed")
        assertThat(row.playlist.description).isEqualTo("Slow morning mix")
        assertThat(row.playlist.coverUris).isEqualTo("content://art/1\ncontent://art/2")
        assertThat(playlists.topArtwork(id))
            .containsExactly("content://art/1", "content://art/2").inOrder()
        assertThat(items.observeItems(id).first()[0].durationMs).isEqualTo(61_000)
    }

    @Test
    fun playlist_move_renumbers_positions() = runBlocking {
        val items = db.playlistItemDao()
        val id = db.playlistDao().insert(PlaylistEntity(name = "P", createdAt = 1))
        items.upsert(playlistItem(id, "m1", 0))
        items.upsert(playlistItem(id, "m2", 1))
        items.upsert(playlistItem(id, "m3", 2))
        items.upsert(playlistItem(id, "m4", 3))

        items.move(id, fromPosition = 3, toPosition = 0)
        assertThat(items.observeItems(id).first().map { it.mediaId })
            .containsExactly("m4", "m1", "m2", "m3").inOrder()
        assertThat(items.observeItems(id).first().map { it.position })
            .containsExactly(0, 1, 2, 3).inOrder()

        // Out-of-range and no-op moves leave the order untouched.
        items.move(id, 0, 0)
        items.move(id, -1, 2)
        items.move(id, 0, 9)
        assertThat(items.observeItems(id).first().map { it.mediaId })
            .containsExactly("m4", "m1", "m2", "m3").inOrder()
    }

    @Test
    fun playlists_containing_and_delete_everywhere() = runBlocking {
        val playlists = db.playlistDao()
        val items = db.playlistItemDao()
        val a = playlists.insert(PlaylistEntity(name = "A", createdAt = 1))
        val b = playlists.insert(PlaylistEntity(name = "B", createdAt = 2))
        items.upsert(playlistItem(a, "m1", 0))
        items.upsert(playlistItem(a, "m2", 1))
        items.upsert(playlistItem(b, "m1", 0))

        assertThat(items.observePlaylistsContaining("m1").first())
            .containsExactly(a, b)
        assertThat(items.observePlaylistsContaining("m2").first())
            .containsExactly(a)
        assertThat(items.playlistIdsContaining("m1")).containsExactly(a, b)

        items.deleteEverywhere("m1")
        assertThat(items.observePlaylistsContaining("m1").first()).isEmpty()
        assertThat(items.observeItems(a).first().map { it.mediaId })
            .containsExactly("m2")
        assertThat(items.observeItems(b).first()).isEmpty()
    }

    /**
     * Real file migration v2 → v3: hand-build the v2 playlist tables (the DDL
     * MIGRATION_1_2 writes), seed rows, then open through Room — rows keep
     * their values and the new columns fall back to their defaults.
     */
    @Test
    fun migration_2_to_3_preserves_rows() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_v3_test.db")
        context.openOrCreateDatabase("migration_v3_test.db", 0, null).use { raw ->
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT, `artworkUri` TEXT, `source` TEXT NOT NULL, `durationMs` INTEGER, " +
                    "`addedAt` INTEGER NOT NULL, `missing` INTEGER NOT NULL)",
            )
            raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_items_position` ON `queue_items` (`position`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue_state` (`id` INTEGER NOT NULL, `currentIndex` INTEGER NOT NULL, " +
                    "`positionMs` INTEGER NOT NULL, `repeatMode` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `play_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `artworkUri` TEXT, " +
                    "`source` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL, " +
                    "`missing` INTEGER NOT NULL)",
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` ON `play_history` (`playedAt`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `favorites` (`mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT, `artworkUri` TEXT, `source` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                    "`missing` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_items` (`playlistId` INTEGER NOT NULL, " +
                    "`mediaId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT, `artworkUri` TEXT, `source` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlistId`, `mediaId`), " +
                    "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` " +
                    "ON `playlist_items` (`playlistId`)",
            )
            raw.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Keep me', 42)")
            raw.execSQL(
                "INSERT INTO playlist_items (playlistId, mediaId, position, title, artist, artworkUri, source, addedAt) " +
                    "VALUES (1, 'm1', 0, 'Song', NULL, NULL, 'LOCAL', 1)",
            )
            raw.version = 2
        }

        val migrated = Room.databaseBuilder(
            context,
            PlaybackDatabase::class.java,
            "migration_v3_test.db",
        ).addMigrations(PlaybackMigrations.MIGRATION_2_3).build()
        try {
            migrated.openHelper.writableDatabase // force the upgrade

            val row = migrated.playlistDao().observeWithCounts().first()
                .single { it.playlist.id == 1L }
            assertThat(row.playlist.name).isEqualTo("Keep me")
            assertThat(row.playlist.description).isEmpty()
            assertThat(row.playlist.coverUris).isEmpty()
            assertThat(row.trackCount).isEqualTo(1)
            assertThat(
                migrated.playlistItemDao().observeItems(1).first().single().durationMs,
            ).isNull()
        } finally {
            migrated.close()
            context.deleteDatabase("migration_v3_test.db")
        }
    }

    /**
     * Real file migration: hand-build a v1 `playback.db` (the v1 DDL from the
     * exported schema), seed favorites + history, then open through Room with
     * MIGRATION_1_2 — v1 rows must survive and the playlist tables must work.
     */
    @Test
    fun migration_1_to_2_preserves_rows() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_test.db")
        context.openOrCreateDatabase("migration_test.db", 0, null).use { raw ->
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT, `artworkUri` TEXT, `source` TEXT NOT NULL, `durationMs` INTEGER, " +
                    "`addedAt` INTEGER NOT NULL, `missing` INTEGER NOT NULL)",
            )
            raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_items_position` ON `queue_items` (`position`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue_state` (`id` INTEGER NOT NULL, `currentIndex` INTEGER NOT NULL, " +
                    "`positionMs` INTEGER NOT NULL, `repeatMode` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `play_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `artworkUri` TEXT, " +
                    "`source` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, `playedMs` INTEGER NOT NULL, " +
                    "`missing` INTEGER NOT NULL)",
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` ON `play_history` (`playedAt`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `favorites` (`mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT, `artworkUri` TEXT, `source` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                    "`missing` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
            )
            raw.execSQL(
                "INSERT INTO favorites (mediaId, title, artist, artworkUri, source, addedAt, missing) " +
                    "VALUES ('pianostream://local/keep', 'Keep', 'A', NULL, 'LOCAL', 1, 0)",
            )
            raw.execSQL(
                "INSERT INTO play_history (mediaId, title, artist, artworkUri, source, playedAt, playedMs, missing) " +
                    "VALUES ('pianostream://stream/vid00000000', 'YT', NULL, NULL, 'YOUTUBE', 5, 1000, 0)",
            )
            raw.version = 1
        }

        val migrated = Room.databaseBuilder(
            context,
            PlaybackDatabase::class.java,
            "migration_test.db",
        ).addMigrations(
            PlaybackMigrations.MIGRATION_1_2,
            PlaybackMigrations.MIGRATION_2_3,
        ).build()
        try {
            migrated.openHelper.writableDatabase // force the upgrade

            assertThat(migrated.favoriteDao().exists("pianostream://local/keep")).isTrue()
            assertThat(migrated.playHistoryDao().observeHistory().first().map { it.mediaId })
                .containsExactly("pianostream://stream/vid00000000")

            val pid = migrated.playlistDao().insert(PlaylistEntity(name = "P", createdAt = 1))
            migrated.playlistItemDao().upsert(playlistItem(pid, "m1", 0))
            assertThat(migrated.playlistItemDao().observeItems(pid).first().map { it.mediaId })
                .containsExactly("m1")
        } finally {
            migrated.close()
            context.deleteDatabase("migration_test.db")
        }
    }
}
