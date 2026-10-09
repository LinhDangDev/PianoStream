package com.pianostream.core.data.playback.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pianostream.core.data.downloads.DownloadDao
import com.pianostream.core.data.downloads.DownloadEntity
import com.pianostream.core.data.playback.db.entity.FavoriteEntity
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import com.pianostream.core.data.playback.db.entity.PlaylistEntity
import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import com.pianostream.core.data.playback.db.entity.QueueStateEntity

@Database(
    entities = [
        QueueItemEntity::class,
        QueueStateEntity::class,
        PlayHistoryEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        DownloadEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun queueDao(): com.pianostream.core.data.playback.db.dao.QueueDao
    abstract fun playHistoryDao(): com.pianostream.core.data.playback.db.dao.PlayHistoryDao
    abstract fun favoriteDao(): com.pianostream.core.data.playback.db.dao.FavoriteDao
    abstract fun playlistDao(): com.pianostream.core.data.playback.db.dao.PlaylistDao
    abstract fun playlistItemDao(): com.pianostream.core.data.playback.db.dao.PlaylistItemDao
    abstract fun maintenanceDao(): com.pianostream.core.data.playback.db.dao.PlaybackMaintenanceDao
    abstract fun downloadDao(): DownloadDao
    companion object {
        const val NAME = "playback.db"
    }
}
