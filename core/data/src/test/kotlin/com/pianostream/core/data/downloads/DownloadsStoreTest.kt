package com.pianostream.core.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.PlaybackMigrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `downloads` table (playback.db v4): entity round-trips through Room,
 * `failActive` sweeps in-flight rows on boot (orphaned .part files are
 * deleted by `DownloadManager`), and a real file migration 3 → 4 keeps
 * existing rows while adding the table.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsStoreTest {

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

    private fun item(id: String, state: String, at: Long = 1) = DownloadEntity(
        videoId = id,
        title = "t-$id",
        artist = null,
        artworkUri = null,
        filePath = "/x/$id.bin",
        bytesDownloaded = 10,
        bytesTotal = 20,
        state = state,
        error = null,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun upsert_observe_orders_by_created_desc() = runBlocking {
        val dao = db.downloadDao()
        dao.upsert(item("a", "DONE", at = 2))
        dao.upsert(item("b", "QUEUED", at = 9))
        dao.upsert(item("a", "DONE", at = 5))

        val rows = dao.observeAll().first()
        assertThat(rows.map { it.videoId }).containsExactly("b", "a").inOrder()
        // REPLACE on videoId — "a" keeps the latest write.
        assertThat(rows.single { it.videoId == "a" }.createdAt).isEqualTo(5)
    }

    @Test
    fun observe_single_and_delete() = runBlocking {
        val dao = db.downloadDao()
        dao.upsert(item("x", "DOWNLOADING"))
        assertThat(dao.observe("x").first()?.state).isEqualTo("DOWNLOADING")
        dao.delete("x")
        assertThat(dao.observe("x").first()).isNull()
        assertThat(dao.get("x")).isNull()
    }

    @Test
    fun failActive_sweeps_only_inflight_rows() = runBlocking {
        val dao = db.downloadDao()
        dao.upsert(item("q", "QUEUED"))
        dao.upsert(item("d", "DOWNLOADING"))
        dao.upsert(item("ok", "DONE"))
        dao.upsert(item("f", "FAILED"))
        dao.failActive("interrupted", updatedAt = 77)

        val rows = dao.observeAll().first().associateBy { it.videoId }
        assertThat(rows.getValue("q").state).isEqualTo("FAILED")
        assertThat(rows.getValue("d").state).isEqualTo("FAILED")
        assertThat(rows.getValue("q").error).isEqualTo("interrupted")
        assertThat(rows.getValue("q").updatedAt).isEqualTo(77)
        assertThat(rows.getValue("ok").state).isEqualTo("DONE")
        assertThat(rows.getValue("f").state).isEqualTo("FAILED")
    }

    @Test
    fun deleteAll_clears_rows() = runBlocking {
        val dao = db.downloadDao()
        dao.upsert(item("a", "DONE"))
        dao.upsert(item("b", "DONE"))
        dao.deleteAll()
        assertThat(dao.observeAll().first()).isEmpty()
    }

    @Test
    fun repository_maps_items_and_ignores_unknown_states() = runBlocking {
        val repo = DownloadRepositoryImpl(db)
        repo.upsert(DownloadItem(videoId = "v1", title = "Song", state = DownloadState.DONE))
        val got = repo.get("v1")!!
        assertThat(got.state).isEqualTo(DownloadState.DONE)
        assertThat(got.isDone).isTrue()

        // An unmapped state string (a future rollout writing STATE_X) maps
        // to FAILED rather than crashing the consumer.
        db.downloadDao().upsert(item("weird", "STATE_X"))
        assertThat(repo.get("weird")!!.state).isEqualTo(DownloadState.FAILED)
    }

    /**
     * Real file migration 3 → 4: build the v3 schema (v2 tables + the
     * MIGRATION_2_3 ALTERs), seed a favorites row, then open through Room —
     * the downloads table must exist and the row must survive.
     */
    @Test
    fun migration_3_to_4_adds_downloads_table() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_v4_test.db")
        context.openOrCreateDatabase("migration_v4_test.db", 0, null).use { raw ->
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
            // The three ALTERs MIGRATION_2_3 performs.
            raw.execSQL("ALTER TABLE `playlists` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
            raw.execSQL("ALTER TABLE `playlists` ADD COLUMN `coverUris` TEXT NOT NULL DEFAULT ''")
            raw.execSQL("ALTER TABLE `playlist_items` ADD COLUMN `durationMs` INTEGER")
            raw.execSQL(
                "INSERT INTO favorites (mediaId, title, artist, artworkUri, source, addedAt, missing) " +
                    "VALUES ('keep', 'Keep me', NULL, NULL, 'YOUTUBE', 1, 0)",
            )
            raw.version = 3
        }

        val migrated = Room.databaseBuilder(
            context,
            PlaybackDatabase::class.java,
            "migration_v4_test.db",
        ).addMigrations(
            PlaybackMigrations.MIGRATION_1_2,
            PlaybackMigrations.MIGRATION_2_3,
            PlaybackMigrations.MIGRATION_3_4,
        ).build()
        try {
            migrated.openHelper.writableDatabase // force the upgrade

            migrated.downloadDao().upsert(item("new", "DONE"))
            assertThat(migrated.downloadDao().get("new")?.state).isEqualTo("DONE")
            assertThat(
                migrated.favoriteDao().observeFavorites().first()
                    .map { it.mediaId },
            ).contains("keep")
        } finally {
            migrated.close()
            context.deleteDatabase("migration_v4_test.db")
        }
    }
}
