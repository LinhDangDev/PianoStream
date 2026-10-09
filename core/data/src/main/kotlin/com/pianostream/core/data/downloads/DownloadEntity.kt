package com.pianostream.core.data.downloads

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `downloads` — keyed by `videoId` (the id component of
 * `pianostream://stream/<videoId>`; the mediaId itself is derivable, so it
 * is never stored). `state` is the [DownloadState] name. v4 schema.
 */
@Entity(tableName = "downloads")
public data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String?,
    val artworkUri: String?,
    val filePath: String?,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val state: String,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
