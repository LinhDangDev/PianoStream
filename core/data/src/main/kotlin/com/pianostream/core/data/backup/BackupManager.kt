package com.pianostream.core.data.backup

import android.content.Context
import android.net.Uri
import com.pianostream.core.data.downloads.DownloadEntity
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.dao.PlayHistoryDao
import com.pianostream.core.data.playback.db.entity.FavoriteEntity
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import com.pianostream.core.data.playback.db.entity.PlaylistEntity
import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import com.pianostream.core.data.playback.db.entity.QueueStateEntity
import com.pianostream.core.data.settings.SettingsBackupPort
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `.psb` backup archives — a ZIP carrying `manifest.json`, `data.json`
 * (every playback.db table worth preserving plus the settings surface) and
 * `downloads/<videoId>.bin` audio payloads.
 *
 * Restore is a merge, never a wipe: rows upsert by primary key with the
 * backup winning conflicts. Queue rows/keyed-by-position are rewritten as a
 * set only when the backup carries a queue section. Download files copy only
 * into `filesDir/downloads/` under a zip-slip guard (`..`, nested dirs and
 * non-`.bin` names are refused), and files already on disk are left alone.
 *
 * All work runs on [Dispatchers.IO]; SAF stream open/write failures surface
 * as [IOException] (or [InvalidBackupException] for a bad manifest) so the
 * caller can render the reason verbatim.
 */
@Singleton
public class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PlaybackDatabase,
    private val settingsBackup: SettingsBackupPort,
) {

    /** The backup manifest fails this check → the archive is not ours. */
    public class InvalidBackupException(message: String) : IOException(message)

    public data class ExportReport(
        val playlists: Int,
        val favorites: Int,
        val downloads: Int,
        val downloadFiles: Int,
        val downloadBytes: Long,
    )

    public data class RestoreReport(
        val playlists: Int,
        val favorites: Int,
        val downloads: Int,
        /** .bin payloads actually copied (pre-existing files are skipped). */
        val downloadFiles: Int,
        /** Restored search history for the caller to hot-apply. */
        val recentSearches: List<String>,
    )

    // --- export ---------------------------------------------------------------

    /** Writes the archive to the SAF document behind [uri]. */
    public suspend fun exportBackup(
        uri: Uri,
        includeDownloadFiles: Boolean,
        appVersion: String,
        recentSearches: List<String>,
    ): ExportReport = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open the chosen backup destination")
        out.use { writeBackup(it, includeDownloadFiles, appVersion, recentSearches) }
    }

    internal suspend fun writeBackup(
        out: OutputStream,
        includeDownloadFiles: Boolean,
        appVersion: String,
        recentSearches: List<String>,
    ): ExportReport {
        val favorites = db.favoriteDao().observeFavorites().first()
        val playlists = db.playlistDao().observeWithCounts().first()
            .map { it.playlist }
        val playlistItems = db.playlistItemDao().snapshotAll()
            .groupBy { it.playlistId }
        val queueItems = db.queueDao().queueSnapshot()
        val queueState = db.queueDao().queueState()
        val history = db.playHistoryDao().observeHistory().first()
        val downloads = db.downloadDao().observeAll().first()

        // File payloads are emitted only for DONE rows whose .bin exists —
        // a DONE row with a missing file exports metadata only.
        val payloadFile = downloads.mapNotNull { e ->
            File(downloadsDir, "${e.videoId}$FILE_SUFFIX")
                .takeIf { it.isFile }
                ?.let { e.videoId to it }
        }.toMap()
        val includedFiles = if (includeDownloadFiles) {
            downloads.filter { e -> e.state == STATE_DONE && payloadFile[e.videoId] != null }
        } else {
            emptyList()
        }
        val downloadBytes = includedFiles.sumOf { payloadFile.getValue(it.videoId).length() }

        val data = buildDataJson(
            favorites = favorites,
            playlists = playlists,
            playlistItems = playlistItems,
            queueItems = queueItems,
            queueState = queueState,
            history = history,
            downloads = downloads,
            includedFileIds = includedFiles.mapTo(HashSet()) { it.videoId },
            recentSearches = recentSearches,
            settings = settingsBackup.exportJson(),
        )

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestJson(appVersion).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ENTRY_DATA))
            zip.write(data.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            includedFiles.forEach { e ->
                val entryName = "$ENTRY_DOWNLOADS_DIR/${safeVideoId(e.videoId)}$FILE_SUFFIX"
                zip.putNextEntry(ZipEntry(entryName))
                payloadFile.getValue(e.videoId).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return ExportReport(
            playlists = playlists.size,
            favorites = favorites.size,
            downloads = downloads.size,
            downloadFiles = includedFiles.size,
            downloadBytes = downloadBytes,
        )
    }

    internal suspend fun buildDataJson(
        favorites: List<FavoriteEntity>,
        playlists: List<PlaylistEntity>,
        playlistItems: Map<Long, List<PlaylistItemEntity>>,
        queueItems: List<QueueItemEntity>,
        queueState: QueueStateEntity?,
        history: List<PlayHistoryEntity>,
        downloads: List<DownloadEntity>,
        includedFileIds: Set<String>,
        recentSearches: List<String>,
        settings: JSONObject,
    ): JSONObject = JSONObject().apply {
        put("favorites", JSONArray().apply {
            favorites.forEach { put(favoriteJson(it)) }
        })
        put("playlists", JSONArray().apply {
            playlists.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("createdAt", p.createdAt)
                    put("description", p.description)
                    put("coverUris", p.coverUris)
                    put("items", JSONArray().apply {
                        playlistItems[p.id].orEmpty().forEach { put(playlistItemJson(it)) }
                    })
                })
            }
        })
        put("queueItems", JSONArray().apply {
            queueItems.forEach { put(queueItemJson(it)) }
        })
        if (queueState != null) put("queueState", queueStateJson(queueState))
        put("playHistory", JSONArray().apply {
            history.forEach { put(historyJson(it)) }
        })
        put("downloads", JSONArray().apply {
            downloads.forEach { e ->
                put(downloadJson(e, fileIncluded = e.videoId in includedFileIds))
            }
        })
        put("recentSearches", JSONArray().apply { recentSearches.forEach { put(it) } })
        put("settings", settings)
    }

    // --- restore ----------------------------------------------------------------

    /**
     * Two passes over [uri]: the first reads `manifest.json` + `data.json`
     * (refusing unknown formats before anything is written), the second
     * extracts `downloads/<videoId>.bin` payloads so row upserts can point
     * `filePath` at bytes that already exist locally.
     */
    public suspend fun restoreBackup(uri: Uri): RestoreReport = withContext(Dispatchers.IO) {
        val parsed = context.contentResolver.openInputStream(uri)?.use { parseBackup(it) }
            ?: throw IOException("Cannot open the chosen backup file")
        val filesCopied = context.contentResolver.openInputStream(uri)?.use {
            extractDownloadFiles(it)
        } ?: 0
        val counts = applyData(parsed.data)
        RestoreReport(
            playlists = counts.playlists,
            favorites = counts.favorites,
            downloads = counts.downloads,
            downloadFiles = filesCopied,
            recentSearches = counts.recentSearches,
        )
    }

    internal class ParsedBackup(val manifest: JSONObject, val data: JSONObject)

    /**
     * Reads `manifest.json` + `data.json` from the archive. Throws
     * [InvalidBackupException] when the manifest is absent, its
     * `formatVersion` isn't 1, or it belongs to a different app. Unknown
     * manifest/data fields are ignored (forward-compat).
     */
    internal fun parseBackup(input: InputStream): ParsedBackup {
        var manifest: JSONObject? = null
        var data: JSONObject? = null
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    ENTRY_MANIFEST -> manifest = JSONObject(readEntryText(zip))
                    ENTRY_DATA -> data = JSONObject(readEntryText(zip))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val m = manifest ?: throw InvalidBackupException("manifest.json missing")
        if (m.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw InvalidBackupException(
                "unsupported formatVersion ${m.opt("formatVersion")}",
            )
        }
        val app = m.optString("app", "")
        if (app.isNotEmpty() && app != APP_ID) {
            throw InvalidBackupException("backup belongs to $app, not $APP_ID")
        }
        return ParsedBackup(m, data ?: throw InvalidBackupException("data.json missing"))
    }

    /**
     * Extracts `downloads/<name>.bin` entries into `filesDir/downloads/`.
     * Zip-slip guard: the entry must normalise to a flat `<name>.bin` inside
     * that one directory — anything else (`..`, nested paths, other
     * prefixes, other extensions) is skipped, and a file already on disk is
     * never overwritten. Returns the number of files copied.
     */
    internal fun extractDownloadFiles(input: InputStream): Int {
        val dir = downloadsDir.apply { mkdirs() }
        val dirPath = dir.canonicalPath + File.separator
        var copied = 0
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = safeDownloadEntryName(entry.name, dirPath)
                if (name != null && !entry.isDirectory) {
                    val target = File(dir, name)
                    if (!target.exists()) {
                        target.outputStream().use { zip.copyTo(it) }
                        copied++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return copied
    }

    /** [entry] → a safe flat filename inside `filesDir/downloads/`, or null. */
    private fun safeDownloadEntryName(entry: String, dirPath: String): String? {
        val normalized = entry.replace('\\', '/')
        if (!normalized.startsWith("$ENTRY_DOWNLOADS_DIR/")) return null
        val rel = normalized.removePrefix("$ENTRY_DOWNLOADS_DIR/")
        // Flat files only — `..`, dot/dotdot names and any further
        // separator are refused (a leading dot can still hide a file).
        if (rel.isEmpty() || rel.startsWith(".") || rel.contains('/')) return null
        if (!rel.endsWith(FILE_SUFFIX)) return null
        val dir = downloadsDir
        if (!File(dir, rel).canonicalPath.startsWith(dirPath)) return null
        return rel
    }

    internal class ApplyCounts(
        val playlists: Int,
        val favorites: Int,
        val downloads: Int,
        val recentSearches: List<String>,
    )

    /**
     * Merges `data.json` into playback.db + settings. Per-section failures
     * are contained ([runCatching] per row) so one bad row can't veto the
     * rest; sections absent from the backup are left untouched.
     */
    internal suspend fun applyData(data: JSONObject): ApplyCounts {
        var playlistCount = 0
        var favoriteCount = 0
        var downloadCount = 0

        data.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { db.favoriteDao().upsert(favoriteEntity(o)) }
                    .onSuccess { favoriteCount++ }
            }
        }

        data.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    val playlist = playlistEntity(o)
                    db.playlistDao().insert(playlist)
                    o.optJSONArray("items")?.let { items ->
                        for (j in 0 until items.length()) {
                            val io = items.optJSONObject(j) ?: continue
                            runCatching {
                                db.playlistItemDao()
                                    .upsert(playlistItemEntity(playlist.id, io))
                            }
                        }
                    }
                }.onSuccess { playlistCount++ }
            }
        }

        data.optJSONArray("queueItems")?.let { arr ->
            val items = buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(::queueItemEntity)?.let(::add)
                }
            }
            if (items.isNotEmpty()) {
                val state = data.optJSONObject("queueState")?.let(::queueStateEntity)
                // Positions are the queue's identity — renumber to array order
                // so a malformed backup can't violate the unique index.
                db.queueDao().replaceQueue(
                    items.mapIndexed { index, item -> item.copy(position = index) },
                    state,
                )
            }
        }

        data.optJSONArray("playHistory")?.let { arr ->
            val existing = db.playHistoryDao().observeHistory().first()
                .mapTo(HashSet()) { it.mediaId to it.playedAt }
            val oldestKept = System.currentTimeMillis() - PlayHistoryDao.MAX_AGE_MS
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i)?.let(::historyEntity) ?: continue
                if (!existing.add(e.mediaId to e.playedAt)) continue // dedupe
                runCatching {
                    db.playHistoryDao().insertAndPrune(e, PlayHistoryDao.MAX_ROWS, oldestKept)
                }
            }
        }

        data.optJSONArray("downloads")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    db.downloadDao().upsert(downloadEntity(o))
                }.onSuccess { downloadCount++ }
            }
        }

        data.optJSONObject("settings")?.let { settingsBackup.importJson(it) }

        val searches = data.optJSONArray("recentSearches")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i, null)?.let(::add)
                }
            }
        } ?: emptyList()

        return ApplyCounts(playlistCount, favoriteCount, downloadCount, searches)
    }

    // --- entity <-> json --------------------------------------------------------

    private fun manifestJson(appVersion: String): JSONObject = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("app", APP_ID)
        .put("appVersion", appVersion)
        .put("exportedAt", System.currentTimeMillis())

    private fun favoriteJson(e: FavoriteEntity) = JSONObject().apply {
        put("mediaId", e.mediaId)
        put("title", e.title)
        put("artist", e.artist ?: JSONObject.NULL)
        put("artworkUri", e.artworkUri ?: JSONObject.NULL)
        put("source", e.source)
        put("addedAt", e.addedAt)
        put("missing", e.missing)
    }

    private fun favoriteEntity(o: JSONObject) = FavoriteEntity(
        mediaId = o.getString("mediaId"),
        title = o.getString("title"),
        artist = o.optNullableString("artist"),
        artworkUri = o.optNullableString("artworkUri"),
        source = o.optString("source", "LOCAL"),
        addedAt = o.optLong("addedAt"),
        missing = o.optBoolean("missing", false),
    )

    private fun playlistEntity(o: JSONObject) = PlaylistEntity(
        id = o.getLong("id"),
        name = o.getString("name"),
        createdAt = o.optLong("createdAt"),
        description = o.optString("description", ""),
        coverUris = o.optString("coverUris", ""),
    )

    private fun playlistItemJson(e: PlaylistItemEntity) = JSONObject().apply {
        put("mediaId", e.mediaId)
        put("position", e.position)
        put("title", e.title)
        put("artist", e.artist ?: JSONObject.NULL)
        put("artworkUri", e.artworkUri ?: JSONObject.NULL)
        put("source", e.source)
        put("addedAt", e.addedAt)
        if (e.durationMs != null) put("durationMs", e.durationMs)
    }

    private fun playlistItemEntity(playlistId: Long, o: JSONObject) = PlaylistItemEntity(
        playlistId = playlistId,
        mediaId = o.getString("mediaId"),
        position = o.optInt("position"),
        title = o.getString("title"),
        artist = o.optNullableString("artist"),
        artworkUri = o.optNullableString("artworkUri"),
        source = o.optString("source", "LOCAL"),
        addedAt = o.optLong("addedAt"),
        durationMs = if (o.has("durationMs") && !o.isNull("durationMs")) {
            o.getLong("durationMs")
        } else {
            null
        },
    )

    private fun queueItemJson(e: QueueItemEntity) = JSONObject().apply {
        put("position", e.position)
        put("mediaId", e.mediaId)
        put("title", e.title)
        put("artist", e.artist ?: JSONObject.NULL)
        put("artworkUri", e.artworkUri ?: JSONObject.NULL)
        put("source", e.source)
        if (e.durationMs != null) put("durationMs", e.durationMs)
        put("addedAt", e.addedAt)
        put("missing", e.missing)
    }

    private fun queueItemEntity(o: JSONObject) = QueueItemEntity(
        id = 0,
        position = o.optInt("position"),
        mediaId = o.getString("mediaId"),
        title = o.getString("title"),
        artist = o.optNullableString("artist"),
        artworkUri = o.optNullableString("artworkUri"),
        source = o.optString("source", "LOCAL"),
        durationMs = if (o.has("durationMs") && !o.isNull("durationMs")) {
            o.getLong("durationMs")
        } else {
            null
        },
        addedAt = o.optLong("addedAt"),
        missing = o.optBoolean("missing", false),
    )

    private fun queueStateJson(e: QueueStateEntity) = JSONObject().apply {
        put("currentIndex", e.currentIndex)
        put("positionMs", e.positionMs)
        put("repeatMode", e.repeatMode)
        put("shuffle", e.shuffle)
        put("updatedAt", e.updatedAt)
    }

    private fun queueStateEntity(o: JSONObject) = QueueStateEntity(
        id = 1,
        currentIndex = o.optInt("currentIndex"),
        positionMs = o.optLong("positionMs"),
        repeatMode = o.optInt("repeatMode"),
        shuffle = o.optBoolean("shuffle", false),
        updatedAt = o.optLong("updatedAt"),
    )

    private fun historyJson(e: PlayHistoryEntity) = JSONObject().apply {
        put("mediaId", e.mediaId)
        put("title", e.title)
        put("artist", e.artist ?: JSONObject.NULL)
        put("artworkUri", e.artworkUri ?: JSONObject.NULL)
        put("source", e.source)
        put("playedAt", e.playedAt)
        put("playedMs", e.playedMs)
        put("missing", e.missing)
    }

    private fun historyEntity(o: JSONObject) = PlayHistoryEntity(
        id = 0,
        mediaId = o.getString("mediaId"),
        title = o.getString("title"),
        artist = o.optNullableString("artist"),
        artworkUri = o.optNullableString("artworkUri"),
        source = o.optString("source", "LOCAL"),
        playedAt = o.optLong("playedAt"),
        playedMs = o.optLong("playedMs"),
        missing = o.optBoolean("missing", false),
    )

    private fun downloadJson(e: DownloadEntity, fileIncluded: Boolean) =
        JSONObject().apply {
            put("videoId", e.videoId)
            put("title", e.title)
            put("artist", e.artist ?: JSONObject.NULL)
            put("artworkUri", e.artworkUri ?: JSONObject.NULL)
            put("filePath", e.filePath ?: JSONObject.NULL)
            put("bytesDownloaded", e.bytesDownloaded)
            put("bytesTotal", e.bytesTotal)
            put("state", e.state)
            put("error", e.error ?: JSONObject.NULL)
            put("createdAt", e.createdAt)
            put("updatedAt", e.updatedAt)
            put("fileIncluded", fileIncluded)
        }

    /**
     * Incoming row wins on PK (videoId). `filePath` is rewritten to this
     * device's `filesDir/downloads/<videoId>.bin` when the payload exists
     * locally (the source path names a foreign `filesDir`); in-flight
     * QUEUED/DOWNLOADING states can't survive a restore — they land as
     * FAILED, matching `DownloadManager`'s process-death sweep.
     */
    private fun downloadEntity(o: JSONObject): DownloadEntity {
        val videoId = o.getString("videoId")
        val local = File(downloadsDir, "${safeVideoId(videoId)}$FILE_SUFFIX")
        val state = o.optString("state", "FAILED").let {
            if (it == "QUEUED" || it == "DOWNLOADING") "FAILED" else it
        }
        return DownloadEntity(
            videoId = videoId,
            title = o.getString("title"),
            artist = o.optNullableString("artist"),
            artworkUri = o.optNullableString("artworkUri"),
            filePath = if (local.isFile) local.absolutePath
            else o.optNullableString("filePath"),
            bytesDownloaded = o.optLong("bytesDownloaded"),
            bytesTotal = o.optLong("bytesTotal"),
            state = state,
            error = o.optNullableString("error")
                ?: if (state != o.optString("state")) "interrupted by restore" else null,
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }

    // --- plumbing -----------------------------------------------------------------

    private val downloadsDir: File get() = File(context.filesDir, "downloads")

    private fun readEntryText(zip: ZipInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        zip.copyTo(out)
        return out.toString(Charsets.UTF_8.name())
    }

    /** videoIds are `[A-Za-z0-9_-]` — anything else can't round-trip as a filename. */
    private fun safeVideoId(videoId: String): String =
        videoId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val FORMAT_VERSION = 1
        const val APP_ID = "com.pianostream"
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_DATA = "data.json"
        const val ENTRY_DOWNLOADS_DIR = "downloads"
        const val FILE_SUFFIX = ".bin"
        const val STATE_DONE = "DONE"
    }
}

/** `{"k": null}` / absent → null; present → string. */
private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
