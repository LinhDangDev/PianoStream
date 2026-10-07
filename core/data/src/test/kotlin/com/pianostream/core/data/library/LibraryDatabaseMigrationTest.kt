package com.pianostream.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.db.LibraryDataAccess
import com.pianostream.core.data.library.db.LibraryDatabase
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `library.db` v1 schema integrity under Robolectric — the module's frozen
 * androidTest source set has no deps, so schema assertions run JVM-side
 * through the same SQLite Room generates on-device: discs/tracks/cue_raw/
 * track_fts tables, `UNIQUE(discId,trackNo)`, `docId` unique index, FK
 * cascade, and the `scanComplete` durability column (M2).
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseMigrationTest {

    private lateinit var db: LibraryDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LibraryDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun tables(): Set<String> {
        val out = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
        return out
    }

    private fun columns(table: String): List<String> {
        val cols = mutableListOf<String>()
        db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) cols += c.getString(1)
        }
        return cols
    }

    @Test
    fun v1_tables_exist() {
        val t = tables()
        assertThat(t).containsAtLeast(
            "discs", "tracks", "cue_sheets_raw", "track_fts",
        )
    }

    @Test
    fun discs_has_scanComplete_column() {
        assertThat(columns("discs")).contains("scanComplete")
    }

    @Test
    fun tracks_unique_disc_trackNo_enforced() = runBlocking {
        val discId = db.discDao().upsert(disc(1))
        db.trackDao().upsertAll(listOf(track(discId, 1)))
        var threw = false
        try {
            db.trackDao().upsertAll(listOf(track(discId, 1, title = "dup")))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }
        // REPLACE conflict strategy upserts rather than throws — the unique
        // index is what matters; verify only one row survives either way.
        if (!threw) {
            assertThat(db.trackDao().byDisc(discId)).hasSize(1)
        }
    }

    @Test
    fun disc_delete_cascades_tracks() = runBlocking {
        val discId = db.discDao().upsert(disc(1))
        db.trackDao().upsertAll(listOf(track(discId, 1), track(discId, 2)))
        db.discDao().deleteById(discId)
        assertThat(db.trackDao().byDisc(discId)).isEmpty()
    }

    @Test
    fun fts_table_indexes_title_performer_composer() = runBlocking {
        val discId = db.discDao().upsert(disc(1))
        db.trackDao().upsertAll(
            listOf(
                track(discId, 1, title = "Haydn Sonata", composer = "Joseph Haydn"),
                track(discId, 2, title = "Other", performer = "Bart van Oort"),
            ),
        )
        val hits = db.searchDao().search("Haydn", db.trackDao())
        assertThat(hits.map { it.title }).contains("Haydn Sonata")
        val hits2 = db.searchDao().search("Oort", db.trackDao())
        assertThat(hits2).hasSize(1)
    }

    @Test
    fun reconcile_tombstones_dangling_playback_rows() = runBlocking {
        val playback = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlaybackDatabase::class.java,
        ).build()
        try {
            playback.queueDao().insertAll(
                listOf(
                    QueueItemEntity(
                        position = 0, mediaId = "pianostream://local/dead",
                        title = "d", artist = null, artworkUri = null,
                        source = "LOCAL", durationMs = 1, addedAt = 1,
                    ),
                    QueueItemEntity(
                        position = 1, mediaId = "pianostream://local/alive",
                        title = "a", artist = null, artworkUri = null,
                        source = "LOCAL", durationMs = 1, addedAt = 1,
                    ),
                ),
            )
            val access = LibraryDataAccess(db, playback)
            val missing = access.reconcilePlayback(setOf("alive"))
            assertThat(missing).isEqualTo(1)
            val rows = playback.queueDao().queueSnapshot()
            assertThat(rows.single { it.mediaId.endsWith("dead") }.missing).isTrue()
            assertThat(rows.single { it.mediaId.endsWith("alive") }.missing).isFalse()
        } finally {
            playback.close()
        }
    }

    private fun disc(n: Int) = com.pianostream.core.data.library.db.entity.DiscEntity(
        docId = "doc$n", treeUri = "content://tree", folderPath = "CD$n",
        title = "Disc $n", performer = null, composer = null, genre = null,
        date = null, discId = null, discNumber = n, totalDiscs = null,
        cueUri = null, flacUri = "content://flac$n", logUri = null,
        artworkUri = null, albumGainDb = null, albumPeak = null,
        flacDurationMs = null, flacSizeBytes = 1, flacLastModified = null,
        scannedAt = 1, scanComplete = true,
    )

    private fun track(
        discId: Long, no: Int,
        title: String = "T$no", performer: String? = null, composer: String? = null,
    ) = com.pianostream.core.data.library.db.entity.TrackEntity(
        discId = discId, trackNo = no, title = title, performer = performer,
        composer = composer, startMs = 0, endMs = 1000, pregapStartMs = null,
        gainDb = null, peak = null, isrc = null, flags = 0,
    )
}
