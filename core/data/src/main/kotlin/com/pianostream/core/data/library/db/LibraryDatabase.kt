package com.pianostream.core.data.library.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pianostream.core.data.library.db.dao.CueSheetRawDao
import com.pianostream.core.data.library.db.dao.DiscDao
import com.pianostream.core.data.library.db.dao.LibrarySearchDao
import com.pianostream.core.data.library.db.dao.TrackDao
import com.pianostream.core.data.library.db.entity.CueSheetRawEntity
import com.pianostream.core.data.library.db.entity.DiscEntity
import com.pianostream.core.data.library.db.entity.TrackEntity
import com.pianostream.core.data.library.db.entity.TrackFtsEntity

/**
 * `library.db` v1 — self-contained: no cross-database joins, ever (CC6).
 */
@Database(
    entities = [
        DiscEntity::class,
        TrackEntity::class,
        CueSheetRawEntity::class,
        TrackFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun discDao(): DiscDao
    abstract fun trackDao(): TrackDao
    abstract fun cueSheetRawDao(): CueSheetRawDao
    abstract fun searchDao(): LibrarySearchDao

    companion object {
        const val NAME = "library.db"
    }
}
