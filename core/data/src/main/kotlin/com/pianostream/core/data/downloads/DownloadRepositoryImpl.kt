package com.pianostream.core.data.downloads

import com.pianostream.core.data.playback.db.PlaybackDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [DownloadRepository] backed by Room `playback.db` (v4 `downloads`). */
@Singleton
public class DownloadRepositoryImpl @Inject constructor(
    db: PlaybackDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DownloadRepository {

    private val dao = db.downloadDao()

    override fun observeAll(): Flow<List<DownloadItem>> =
        dao.observeAll().map { rows -> rows.map(::toItem) }

    override fun observe(videoId: String): Flow<DownloadItem?> =
        dao.observe(videoId).map { it?.let(::toItem) }

    override suspend fun get(videoId: String): DownloadItem? =
        dao.get(videoId)?.let(::toItem)

    override suspend fun upsert(item: DownloadItem) {
        dao.upsert(toEntity(item))
    }

    override suspend fun delete(videoId: String) {
        dao.delete(videoId)
    }

    override suspend fun clear() {
        dao.deleteAll()
    }

    override suspend fun failActive(error: String) {
        dao.failActive(error, nowMs())
    }

    private fun toItem(e: DownloadEntity) = DownloadItem(
        videoId = e.videoId,
        title = e.title,
        artist = e.artist,
        artworkUri = e.artworkUri,
        filePath = e.filePath,
        bytesDownloaded = e.bytesDownloaded,
        bytesTotal = e.bytesTotal,
        state = runCatching { DownloadState.valueOf(e.state) }
            .getOrDefault(DownloadState.FAILED),
        error = e.error,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    private fun toEntity(i: DownloadItem) = DownloadEntity(
        videoId = i.videoId,
        title = i.title,
        artist = i.artist,
        artworkUri = i.artworkUri,
        filePath = i.filePath,
        bytesDownloaded = i.bytesDownloaded,
        bytesTotal = i.bytesTotal,
        state = i.state.name,
        error = i.error,
        createdAt = i.createdAt,
        updatedAt = i.updatedAt,
    )
}
