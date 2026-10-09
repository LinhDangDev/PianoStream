package com.pianostream.core.media.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.pianostream.core.local.saf.ContentResolverSafSource
import com.pianostream.core.local.saf.SafDocumentSource
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Local `.lrc` lookup seam — returns the raw file text beside a track. */
interface LocalLrcSource {
    suspend fun find(localFileUri: String): String?
}

/** Remote lyric fetch seam ([LrclibClient]); tests substitute a fake. */
interface RemoteLrcSource {
    suspend fun fetch(query: LyricsQuery): String?
}

/**
 * Sibling-`.lrc` lookup for a track's own URI:
 *  - `file://` / bare path → `File.parentFile` listing, `<base>.lrc`
 *    matched case-insensitively ("same basename, any case").
 *  - SAF tree document URI → parent folder listed through
 *    [SafDocumentSource] (same cursor seam the library scanner uses, never
 *    DocumentFile per-file IPC), then `ContentResolver` reads the match.
 *  - Any other `content://` grant exposes no sibling listing → null, and
 *    the repository falls through to the remote source.
 */
class SafLocalLrcSource(
    private val context: Context,
    private val safSource: SafDocumentSource,
    private val openInput: (Uri) -> java.io.InputStream? =
        { uri -> context.contentResolver.openInputStream(uri) },
) : LocalLrcSource {

    override suspend fun find(localFileUri: String): String? {
        val uri = Uri.parse(localFileUri)
        return when {
            uri.scheme == null || uri.scheme == "file" -> findOnDisk(uri)
            DocumentsContract.isTreeUri(uri) &&
                uri.path?.contains("/document/") == true -> findInSafTree(uri)
            else -> null
        }
    }

    private fun findOnDisk(uri: Uri): String? {
        val file = File(uri.path ?: return null)
        val base = file.name.substringBeforeLast('.')
        val lrc = file.parentFile?.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("$base.lrc", ignoreCase = true) }
            ?: return null
        return runCatching { lrc.readText() }.getOrNull()
    }

    private suspend fun findInSafTree(uri: Uri): String? {
        val treeDocId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val docId = DocumentsContract.getDocumentId(uri) ?: return null
        val treeUri = DocumentsContract.buildTreeDocumentUri(uri.authority, treeDocId)
        // SAF document ids are "<volume>:<path>" for ExternalStorageProvider;
        // the parent folder is the id minus its last path segment.
        val parentDocId = docId.substringBeforeLast('/', missingDelimiterValue = treeDocId)
        val fileName = Uri.decode(docId.substringAfterLast('/'))
        val target = "${fileName.substringBeforeLast('.')}.lrc"
        val doc = safSource.children(treeUri, parentDocId)
            .firstOrNull { it.displayName.equals(target, ignoreCase = true) }
            ?: return null
        return runCatching {
            openInput(Uri.parse(doc.uri))
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }
}

/**
 * Resolution order (per spec): cache → sibling `.lrc` when
 * [LyricsQuery.localFileUri] is set → LRClib unless [offlineOnly].
 * A found-but-unusable local file (unparseable / blank) does not suppress
 * the remote attempt — it produced no [Lyrics]. Misses are never cached.
 */
class LyricsRepositoryImpl(
    private val local: LocalLrcSource,
    private val remote: RemoteLrcSource,
    private val cache: LyricsCache,
    private val offlineOnly: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsRepository {

    override suspend fun get(query: LyricsQuery): Lyrics? = withContext(io) {
        cache.get(query.mediaId)?.let { return@withContext LrcParser.toLyrics(it) }

        val localRaw = query.localFileUri
            ?.let { runCatching { local.find(it) }.getOrNull() }
        val localLyrics = localRaw?.let(LrcParser::toLyrics)
        if (localLyrics != null) {
            cache.put(query.mediaId, localRaw)
            return@withContext localLyrics
        }

        if (offlineOnly()) return@withContext null
        val remoteRaw = runCatching { remote.fetch(query) }.getOrNull()
            ?: return@withContext null
        cache.put(query.mediaId, remoteRaw)
        LrcParser.toLyrics(remoteRaw)
    }
}

/**
 * INTEGRATION mount — `MediaModule` is owned elsewhere, so providers wire
 * this factory instead of editing it:
 *   lyricsRepository(context, okHttp) { runBlocking { settings.localOnlySearch.first() } }
 */
fun createLyricsRepository(
    context: Context,
    okHttp: OkHttpClient = OkHttpClient(),
    offlineOnly: () -> Boolean = { false },
): LyricsRepository = LyricsRepositoryImpl(
    local = SafLocalLrcSource(context, ContentResolverSafSource(context.contentResolver)),
    remote = LrclibClient(okHttp),
    cache = LyricsCache(context),
    offlineOnly = offlineOnly,
)
