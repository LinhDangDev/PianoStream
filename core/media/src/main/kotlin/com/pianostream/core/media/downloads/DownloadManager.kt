package com.pianostream.core.media.downloads

import android.content.Context
import android.util.Log
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadRepository
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.data.util.Redact
import com.pianostream.core.youtube.api.AudioStreamResolver
import com.pianostream.core.youtube.api.StreamResolver
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Offline audio downloads for `pianostream://stream/<videoId>` items —
 * fully client-side, no backend:
 *
 *  - Resolves the stream URL through the same [StreamResolver] the player
 *    uses (honouring the audio-quality itag via [audioItagProvider]), then
 *    streams the bytes to `filesDir/downloads/<videoId>.bin`.
 *  - `filesDir` (not `cacheDir`) so the OS never reclaims user downloads.
 *    A `.part` staging file absorbs the write and is renamed atomically on
 *    completion, so the playback path either sees the whole download or
 *    falls back to the live network resolve — never a truncated file.
 *  - A plain supervisor [CoroutineScope] (Dispatchers.IO) drives one job
 *    per videoId — no WorkManager dependency on the classpath. At boot the
 *    manager sweeps rows still QUEUED/DOWNLOADING to FAILED("interrupted")
 *    and deletes orphan `.part` files, so process death leaves a clean,
 *    retryable record instead of a stuck "downloading" row.
 *  - [fileFor] is a synchronous, non-blocking lookup the
 *    `StreamResolvingDataSource` calls on the playback thread; a DONE row
 *    plus a present file means "serve local bytes with zero network".
 *
 * State of record is [DownloadRepository] (playback.db `downloads` table);
 * [downloads] mirrors it into memory for sync reads.
 */
class DownloadManager(
    context: Context,
    private val resolver: StreamResolver,
    private val okHttp: OkHttpClient,
    private val repository: DownloadRepository,
    private val audioItagProvider: () -> Int? = { null },
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val dir = File(context.filesDir, DIR_NAME)

    private val jobsLock = Any()
    private val jobs = HashMap<String, Job>()

    /** videoId → record, mirrored from the store for sync reads (fileFor). */
    private val _downloads = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadItem>> = _downloads.asStateFlow()

    init {
        scope.launch {
            repository.observeAll().collect { list ->
                _downloads.value = list.associateBy { it.videoId }
            }
        }
        scope.launch { recoverInterrupted() }
    }

    /**
     * The finished file for [videoId], or null — safe to call on the
     * playback thread: never blocks, never resolves, never touches the
     * network. Returns null unless the row is DONE and the file is there.
     */
    fun fileFor(videoId: String): File? {
        if (_downloads.value[videoId]?.state != DownloadState.DONE) return null
        return File(dir, fileName(videoId))
            .takeIf { it.isFile && it.length() > 0L }
    }

    /** Current record for [videoId] (any state), or null. */
    fun stateFor(videoId: String): DownloadItem? = _downloads.value[videoId]

    /**
     * Queues a download — no-op when one is already DONE (file present) or
     * in flight. Retries of FAILED/interrupted rows reuse the same path.
     */
    fun enqueue(
        videoId: String,
        title: String,
        artist: String? = null,
        artworkUri: String? = null,
    ) {
        val existing = _downloads.value[videoId]
        if (existing?.state == DownloadState.DONE && fileFor(videoId) != null) return
        synchronized(jobsLock) {
            if (jobs.containsKey(videoId)) return
            jobs[videoId] = scope.launch {
                runDownload(videoId, title, artist, artworkUri)
            }
        }
    }

    /** Retries using the stored row's metadata (FAILED/interrupted rows). */
    suspend fun retry(videoId: String) {
        val item = repository.get(videoId) ?: return
        enqueue(videoId, item.title, item.artist, item.artworkUri)
    }

    /** Removes the download entirely: cancels an in-flight job, deletes
     *  `.part` and `.bin`, then drops the record. Idempotent. */
    suspend fun remove(videoId: String) {
        synchronized(jobsLock) { jobs.remove(videoId) }?.cancel()
        runCatching { File(dir, partName(videoId)).delete() }
        runCatching { File(dir, fileName(videoId)).delete() }
        repository.delete(videoId)
    }

    /** Every download — files and rows (Settings → "Clear all downloads"). */
    suspend fun clearAll() {
        val running = synchronized(jobsLock) {
            val list = jobs.values.toList()
            jobs.clear()
            list
        }
        running.forEach { it.cancel() }
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        repository.clear()
    }

    /** Test hook: returns once no download job is running. */
    suspend fun awaitIdle() {
        while (true) {
            val running = synchronized(jobsLock) { jobs.values.toList() }
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    private suspend fun runDownload(
        videoId: String,
        title: String,
        artist: String?,
        artworkUri: String?,
    ) {
        val part = File(dir, partName(videoId))
        val final = File(dir, fileName(videoId))
        try {
            dir.mkdirs()
            upsert(videoId, title, artist, artworkUri, state = DownloadState.QUEUED)

            val resolved = resolve(videoId)
            val total = resolved.contentLength
            upsert(
                videoId, title, artist, artworkUri,
                state = DownloadState.DOWNLOADING,
                bytesDownloaded = 0,
                bytesTotal = total,
            )

            val request = Request.Builder()
                .url(resolved.url)
                .apply { resolved.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} on $videoId")
                }
                val body = response.body
                    ?: throw IOException("empty body for $videoId")
                val bodyTotal = body.contentLength()
                val expected = if (bodyTotal > 0L) bodyTotal else total
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(COPY_BUFFER_BYTES)
                        var written = 0L
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastReport >= PROGRESS_REPORT_BYTES) {
                                lastReport = written
                                upsert(
                                    videoId, title, artist, artworkUri,
                                    state = DownloadState.DOWNLOADING,
                                    bytesDownloaded = written,
                                    bytesTotal = expected,
                                )
                            }
                        }
                    }
                }
            }

            if (final.exists() && !final.delete()) {
                throw IOException("could not replace $final for $videoId")
            }
            if (!part.renameTo(final)) {
                throw IOException("atomic rename failed for $videoId")
            }
            val size = final.length()
            upsert(
                videoId, title, artist, artworkUri,
                state = DownloadState.DONE,
                filePath = final.absolutePath,
                bytesDownloaded = size,
                bytesTotal = size,
            )
        } catch (e: CancellationException) {
            // remove() already dropped the row and the part file; just stop.
            runCatching { part.delete() }
            throw e
        } catch (e: Throwable) {
            runCatching { part.delete() }
            logd("download failed for $videoId: ${e.javaClass.simpleName}")
            upsert(
                videoId, title, artist, artworkUri,
                state = DownloadState.FAILED,
                error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            )
        } finally {
            synchronized(jobsLock) { jobs.remove(videoId) }
        }
    }

    private suspend fun resolve(videoId: String) =
        if (resolver is AudioStreamResolver) {
            resolver.resolveAudio(videoId, audioItagProvider())
        } else {
            resolver.resolve(videoId, audioItagProvider() ?: DEFAULT_AUDIO_ITAG)
        }

    /** Rows abandoned mid-flight (process death) become retryable FAILED;
     *  orphan `.part` files are swept — they never serve playback. */
    private suspend fun recoverInterrupted() {
        runCatching { repository.failActive("Interrupted — tap download to retry") }
        runCatching {
            dir.listFiles()
                ?.filter { it.name.endsWith(PART_SUFFIX) }
                ?.forEach { it.delete() }
        }
    }

    private suspend fun upsert(
        videoId: String,
        title: String,
        artist: String?,
        artworkUri: String?,
        state: DownloadState,
        filePath: String? = null,
        bytesDownloaded: Long = -1L,
        bytesTotal: Long = -1L,
        error: String? = null,
    ) {
        val prev = repository.get(videoId)
        repository.upsert(
            DownloadItem(
                videoId = videoId,
                title = title,
                artist = artist,
                artworkUri = artworkUri,
                filePath = filePath ?: prev?.filePath,
                bytesDownloaded = if (bytesDownloaded >= 0L) {
                    bytesDownloaded
                } else {
                    prev?.bytesDownloaded ?: 0L
                },
                bytesTotal = if (bytesTotal >= 0L) {
                    bytesTotal
                } else {
                    prev?.bytesTotal ?: 0L
                },
                state = state,
                error = error,
                createdAt = prev?.createdAt ?: nowMs(),
                updatedAt = nowMs(),
            ),
        )
    }

    private fun logd(msg: String) {
        // runCatching: android.util.Log is unmocked on plain-JVM unit tests.
        runCatching {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, Redact.url(msg))
        }
    }

    private fun fileName(videoId: String): String = "$videoId$FILE_SUFFIX"
    private fun partName(videoId: String): String = "$videoId$PART_SUFFIX"

    private companion object {
        const val TAG = "DownloadManager"
        const val DIR_NAME = "downloads"
        const val FILE_SUFFIX = ".bin"
        const val PART_SUFFIX = ".part"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_REPORT_BYTES = 256 * 1024L
        const val DEFAULT_AUDIO_ITAG = 251
    }
}
