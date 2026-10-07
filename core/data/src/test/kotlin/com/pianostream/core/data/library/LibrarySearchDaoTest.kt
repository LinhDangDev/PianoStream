package com.pianostream.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.library.db.LibraryDatabase
import com.pianostream.core.data.library.db.dao.LibrarySearchDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F16 adversarial queries — every input is sanitized to a phrase query or
 * falls back to `LIKE`; none can raise `SQLiteException`.
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySearchDaoTest {

    private lateinit var db: LibraryDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LibraryDatabase::class.java,
        ).build()
        runBlocking {
            val id = db.discDao().upsert(disc())
            db.trackDao().upsertAll(
                listOf(
                    track(id, 1, "Haydn Sonata in C", "Bart van Oort", "Joseph Haydn"),
                    track(id, 2, "Chopin Nocturne", "Arthur Rubinstein", "Frédéric Chopin"),
                ),
            )
        }
    }

    @After
    fun close() = db.close()

    @Test
    fun sanitize_produces_safe_phrase() {
        assertThat(LibrarySearchDao.sanitize("Haydn")).isEqualTo("\"Haydn\"")
        assertThat(LibrarySearchDao.sanitize("\"OR\"")).isNotNull()
        assertThat(LibrarySearchDao.sanitize("")).isNull()
        assertThat(LibrarySearchDao.sanitize("   ")).isNull()
        assertThat(LibrarySearchDao.sanitize("*^:−")).isNull()
        assertThat(
            LibrarySearchDao.sanitize("x".repeat(10_000))!!.length,
        ).isAtMost(LibrarySearchDao.MAX_QUERY_LEN + 2)
    }

    @Test
    fun hostile_queries_never_throw() = runBlocking {
        for (q in listOf(
            "\"", "*", "OR", "NEAR(", "", "a".repeat(10_000),
            "Chopin\" OR 1=1 --", "'; DROP TABLE tracks;--",
            "(*)", "FTS5 (x)",
        )) {
            // contract: returns (possibly empty), never throws
            db.searchDao().search(q, db.trackDao())
        }
        Unit
    }

    @Test
    fun normal_query_finds_via_fts(): Unit = runBlocking {
        assertThat(db.searchDao().search("Haydn", db.trackDao())).isNotEmpty()
        assertThat(db.searchDao().search("Oort", db.trackDao())).isNotEmpty()
        assertThat(db.searchDao().search("Chopin", db.trackDao())).isNotEmpty()
        assertThat(db.searchDao().search("zzzz", db.trackDao())).isEmpty()
    }

    @Test
    fun tracks_searchable_on_composer_from_songwriter(): Unit = runBlocking {
        assertThat(db.trackDao().distinctComposers())
            .containsAtLeast("Joseph Haydn", "Frédéric Chopin")
    }

    private fun disc() = com.pianostream.core.data.library.db.entity.DiscEntity(
        docId = "d", treeUri = "content://t", folderPath = "CD1", title = "D",
        performer = null, composer = null, genre = null, date = null,
        discId = null, discNumber = 1, totalDiscs = null, cueUri = null,
        flacUri = "content://f", logUri = null, artworkUri = null,
        albumGainDb = null, albumPeak = null, flacDurationMs = null,
        flacSizeBytes = 1, flacLastModified = null, scannedAt = 1,
        scanComplete = true,
    )

    private fun track(
        discId: Long, no: Int, title: String, performer: String, composer: String,
    ) = com.pianostream.core.data.library.db.entity.TrackEntity(
        discId = discId, trackNo = no, title = title, performer = performer,
        composer = composer, startMs = 0, endMs = 1000, pregapStartMs = null,
        gainDb = null, peak = null, isrc = null, flags = 0,
    )
}
