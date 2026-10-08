package com.pianostream.core.data.playback.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `playback.db` schema migrations. Never destructive — favorites, history and
 * the queue survive every upgrade (the only wipe path is `clearAll`, CC10).
 */
object PlaybackMigrations {

    /** v1 → v2: user playlists (`playlists` + `playlist_items`, FK cascade). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL" +
                    ")",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_items` (" +
                    "`playlistId` INTEGER NOT NULL, " +
                    "`mediaId` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`artist` TEXT, " +
                    "`artworkUri` TEXT, " +
                    "`source` TEXT NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlistId`, `mediaId`), " +
                    "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` " +
                    "ON `playlist_items` (`playlistId`)",
            )
        }
    }
}
