package com.pianostream.core.local.scan

import android.net.Uri
import com.pianostream.core.local.cue.CueParser
import com.pianostream.core.local.cue.CueSheet
import com.pianostream.core.local.saf.SafDocument
import com.pianostream.core.local.saf.SafDocumentKind
import com.pianostream.core.local.saf.SafDocumentSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * SAF tree → parsed-disc pipeline (Phase 3): content-driven descent,
 * per-disc cue+flac reads under the F20 guards, artwork resolution, then a
 * per-disc `DiscScan` ready for `LibraryRepositoryImpl` to persist in one
 * `@Transaction`. `LibraryScanner` itself holds **no** Room handles — it is
 * pure SAF+cue logic so the hostile-input suite runs fully in JVM tests.
 *
 * `SafDocumentSource` supplies folder children; `openRead(uri)` is the
 * ContentResolver seam (≤1 MiB enforced by the caller's `size` guard);
 * `tagReader` is the MediaMetadataRetriever seam for loose-file singles.
 *
 * Singles: any audio doc (`AUDIO` or `FLAC` kind) in a scanned folder that is
 * not named by a `.cue` `FILE` entry becomes a [SingleScan] — folders without
 * a cue, the picked root's own loose files, and orphan audio sitting beside a
 * cue disc. Disc folders still stop descending at the `.cue` — singles are
 * collected from the folder's own doc list, never from deeper subtrees.
 */
class LibraryScanner(
    private val source: SafDocumentSource,
    private val tagReader: AudioTagReader = AudioTagReader { null },
    private val openRead: suspend (uri: String) -> java.io.InputStream?,
) {

    data class DiscScan(
        val folderPath: String,
        val docId: String,
        val cue: SafDocument,
        val flac: SafDocument,
        val log: SafDocument?,
        val artworkUri: String?,
        val sheet: CueSheet,
        val windows: CueSheet.WindowResult,
        val flacDurationMs: Long?,
        val cueContentHash: String,
        val parserVersion: Int,
        val warnings: List<String>,
    )

    /**
     * One loose audio file as a synthesized single-track "disc". [folderPath]
     * is the pseudo path `"<parent>/<filename>"` — unique per file, stable
     * across rescans, and feeds `LibraryHasher.contentKey`/`discKey` exactly
     * like a real disc folder. [docId] is the file's own SAF document id.
     */
    data class SingleScan(
        val folderPath: String,
        val docId: String,
        val audio: SafDocument,
        val artworkUri: String?,
        val title: String,
        val performer: String?,
        val album: String?,
        val genre: String?,
        val date: String?,
        val durationMs: Long?,
        /** Embedded picture bytes; the driver writes them to the artwork cache. */
        val embeddedArt: ByteArray?,
    )

    data class TreeScan(
        val discs: List<DiscScan>,
        val singles: List<SingleScan>,
        val jpegCount: Int,
        val oversizeSkipped: Int,
        val nonAudioFiles: Int,
        val warnings: List<String>,
    )

    private val walkMutex = Mutex()

    /**
     * Walks [treeUri]: depth-bounded, content-driven descent, prunes
     * non-audio, collects jpeg counts; per disc folder picks the single
     * `.cue` + sibling `FILE` flac, parses, reads STREAMINFO, resolves
     * artwork. Loose audio (not referenced by any cue `FILE`) becomes
     * singles — including files sitting at the picked root itself.
     * First `SecurityException` aborts (M2 — rethrown so the repository
     * can keep previous rows and report `grantLost`).
     */
    suspend fun scan(treeUri: Uri): TreeScan = walkMutex.withLock {
        val warnings = ArrayList<String>()
        val discs = ArrayList<DiscScan>()
        val singles = ArrayList<SingleScan>()
        var jpegs = 0
        var oversize = 0
        var nonAudio = 0

        val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocs = source.children(treeUri, rootDocId)

        fun docIdOf(doc: SafDocument) = doc.docId

        suspend fun visit(
            parentDocId: String,
            docs: List<SafDocument>,
            depth: Int,
            path: String,
        ) {
            jpegs += docs.count { it.kind == SafDocumentKind.JPEG }
            nonAudio += docs.count { it.kind == SafDocumentKind.OTHER }
            val cues = docs.filter { it.kind == SafDocumentKind.CUE }
            if (ScanPlan.isDiscFolder(cues.isNotEmpty())) {
                scanDiscFolder(path, parentDocId, docs, cues,
                    rootDocs, warnings, singles)?.let { discs += it }
                return // content-driven stop: no descent inside a disc folder
            }
            collectSingles(path, docs, referenced = emptySet(), rootDocs, singles)
            if (!ScanPlan.canDescend(depth)) {
                if (docs.any { it.kind == SafDocumentKind.FOLDER }) {
                    warnings += "depth bound reached at $path"
                }
                return
            }
            for (dir in docs.filter { it.kind == SafDocumentKind.FOLDER }) {
                val kids = source.children(treeUri, docIdOf(dir))
                visit(docIdOf(dir), kids, depth + 1, "$path/${dir.displayName}")
            }
        }

        // depth 0 = picked root itself; its children are depth 1.
        for (dir in rootDocs.filter { it.kind == SafDocumentKind.FOLDER }) {
            val kids = source.children(treeUri, docIdOf(dir))
            // a depth-1 folder that directly holds a .cue is a disc folder.
            visit(docIdOf(dir), kids, 1, dir.displayName)
        }
        // the root itself may also hold a .cue (picked directly on a disc).
        val rootCues = rootDocs.filter { it.kind == SafDocumentKind.CUE }
        if (rootCues.isNotEmpty()) {
            scanDiscFolder("", rootDocId, rootDocs, rootCues,
                rootDocs, warnings, singles)?.let { discs += it }
        } else {
            // root-level loose audio files are singles too.
            collectSingles("", rootDocs, referenced = emptySet(), rootDocs, singles)
        }

        val compact = ArrayList<DiscScan>()
        for (d in discs) compact += d
        oversize = warnings.count { it.contains("oversize") }
        TreeScan(compact, singles, jpegs, oversize, nonAudio, warnings)
    }

    /**
     * Singles inside a disc folder: every cue's FILE name (the primary
     * [sheet]'s plus any secondary cue that still parses) stays bound to a
     * disc — only audio docs NOT referenced by any cue FILE entry become
     * singles (orphan flac/mp3/wav). Runs even when the primary cue is
     * unreadable: an unparseable cue references nothing, so all the folder's
     * audio drops into the singles pool.
     */
    private suspend fun collectSinglesForDiscFolder(
        path: String,
        docs: List<SafDocument>,
        cues: List<SafDocument>,
        sheet: CueSheet?,
        rootDocs: List<SafDocument>,
        out: MutableList<SingleScan>,
    ) {
        val referenced = HashSet<String>()
        sheet?.fileName?.lowercase()?.let { referenced += it }
        for (extraCue in cues.drop(1)) {
            if (extraCue.size > ScanPlan.MAX_READ_BYTES) continue
            val bytes = openRead(extraCue.uri)?.use { it.readBytes() } ?: continue
            try {
                referenced += CueParser.parse(bytes, path).fileName.lowercase()
            } catch (e: Exception) {
                // secondary sheets are consulted for FILE names only — the
                // primary cue's diagnostics already cover parse failures.
            }
        }
        collectSingles(path, docs, referenced, rootDocs, out)
    }

    /** Every audio doc not named (case-insensitively) in [referenced] is a single. */
    private suspend fun collectSingles(
        path: String,
        docs: List<SafDocument>,
        referenced: Set<String>,
        rootDocs: List<SafDocument>,
        out: MutableList<SingleScan>,
    ) {
        // one folder JPEG covers every loose file in the folder.
        val artworkUri = ArtworkResolver.resolve(docs, rootDocs)
        for (doc in docs) {
            if (doc.kind != SafDocumentKind.AUDIO && doc.kind != SafDocumentKind.FLAC) continue
            if (doc.displayName.lowercase() in referenced) continue
            out += scanSingle(path, artworkUri, doc)
        }
    }

    /**
     * Loose doc → single-track disc metadata: tags via [tagReader], fallbacks
     * title = filename stem and album = parent folder name; FLAC duration is
     * read from STREAMINFO first (container truth, no tag dependency), tag
     * duration next. Artwork = the folder JPEG resolved by the caller else
     * null (the UI shows the vinyl placeholder; embedded art travels in
     * [SingleScan.embeddedArt] for the cache write).
     */
    private suspend fun scanSingle(
        path: String,
        artworkUri: String?,
        doc: SafDocument,
    ): SingleScan {
        val tags = tagReader.read(doc.uri)
        val stem = doc.displayName.substringBeforeLast('.')
        val title = tags?.title?.takeIf { it.isNotBlank() } ?: stem
        val parentName = path.substringAfterLast('/').ifBlank { null }
        val streamInfoDuration = if (doc.kind == SafDocumentKind.FLAC) {
            openRead(doc.uri)?.use { FlacHeaderReader.readStreamInfo(it)?.durationMs }
        } else {
            null
        }
        return SingleScan(
            folderPath = listOf(path, doc.displayName)
                .filter { it.isNotEmpty() }
                .joinToString("/"),
            docId = doc.docId,
            audio = doc,
            artworkUri = artworkUri,
            title = title,
            performer = tags?.artist?.takeIf { it.isNotBlank() },
            album = tags?.album?.takeIf { it.isNotBlank() } ?: parentName,
            genre = tags?.genre?.takeIf { it.isNotBlank() },
            date = tags?.date?.takeIf { it.isNotBlank() },
            durationMs = streamInfoDuration?.takeIf { it > 0 } ?: tags?.durationMs,
            embeddedArt = tags?.embeddedArt,
        )
    }

    private suspend fun scanDiscFolder(
        path: String,
        docId: String,
        docs: List<SafDocument>,
        cues: List<SafDocument>,
        rootDocs: List<SafDocument>,
        warnings: MutableList<String>,
        singles: MutableList<SingleScan>,
    ): DiscScan? {
        val w = warnings
        val cue = cues.first()
        if (cue.size > ScanPlan.MAX_READ_BYTES) {
            w += "$path: cue oversize ${cue.size}B — skipped"
            collectSinglesForDiscFolder(path, docs, cues, null, rootDocs, singles)
            return null
        }
        val cueBytes = openRead(cue.uri)?.use { it.readBytes() }
            ?: run {
                w += "$path: cue unreadable"
                collectSinglesForDiscFolder(path, docs, cues, null, rootDocs, singles)
                return null
            }
        val cueHash = sha256Hex(cueBytes)
        val sheet = try {
            CueParser.parse(cueBytes, path)
        } catch (e: com.pianostream.core.local.cue.CueParseException) {
            w += "$path: ${e.message}"
            null
        }
        // FILE target must be a sibling document of the same folder —
        // '..', '/', '\\' and absolute/foreign paths are rejected before
        // any read (CC7/CC11): a valid name equals a displayName exactly.
        // Any audio kind matches: a cue may target wav/mp3, not only flac.
        val wanted = sheet?.fileName
        val fileOk = wanted != null &&
            !wanted.contains('/') && !wanted.contains('\\') &&
            !wanted.contains("..")
        val flac = if (fileOk) {
            docs.firstOrNull {
                (it.kind == SafDocumentKind.FLAC || it.kind == SafDocumentKind.AUDIO) &&
                    it.displayName.equals(wanted, ignoreCase = true)
            } ?: run {
                w += "$path: FILE \"${sheet?.fileName}\" not a sibling document"
                null
            }
        } else {
            if (wanted != null) w += "$path: FILE \"$wanted\" escapes disc folder"
            null
        }
        collectSinglesForDiscFolder(path, docs, cues, sheet, rootDocs, singles)
        val log = docs.firstOrNull { it.kind == SafDocumentKind.LOG }
        if (log != null && log.size > ScanPlan.MAX_READ_BYTES) {
            w += "$path: log oversize ${log.size}B"
        }
        val artwork = ArtworkResolver.resolve(docs, rootDocs)
        val flacDuration = flac?.let { f ->
            openRead(f.uri)?.use { FlacHeaderReader.readStreamInfo(it)?.durationMs }
                ?: tagReader.read(f.uri)?.durationMs
                ?: run {
                    w += "$path: unreadable STREAMINFO"
                    null
                }
        }
        val windows = sheet?.windows(flacDuration)
            ?: CueSheet.WindowResult(emptyList(), listOf("unparseable"), 0)
        return DiscScan(
            folderPath = path, docId = docId, cue = cue,
            flac = flac ?: dummyFlac(), log = log?.takeIf { it.size <= ScanPlan.MAX_READ_BYTES },
            artworkUri = artwork,
            sheet = sheet ?: CueSheet(fileName = "", tracks = emptyList()),
            windows = windows,
            flacDurationMs = flacDuration,
            cueContentHash = cueHash,
            parserVersion = CueParser.PARSER_VERSION,
            warnings = w.toList(),
        )
    }

    private fun dummyFlac() = SafDocument(
        docId = "", uri = "", displayName = "", mimeType = "",
        size = 0, lastModified = 0, kind = SafDocumentKind.FLAC,
    )

    /** Artwork root-stray fallback needs the tree-root listing (E3). */

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
