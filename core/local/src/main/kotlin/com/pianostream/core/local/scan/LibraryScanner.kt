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
 * ContentResolver seam (≤1 MiB enforced by the caller's `size` guard).
 */
class LibraryScanner(
    private val source: SafDocumentSource,
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

    data class TreeScan(
        val discs: List<DiscScan>,
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
     * artwork. First `SecurityException` aborts (M2 — rethrown so the
     * repository can keep previous rows and report `grantLost`).
     */
    suspend fun scan(treeUri: Uri): TreeScan = walkMutex.withLock {
        val warnings = ArrayList<String>()
        val discs = ArrayList<DiscScan>()
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
                scanDiscFolder(treeUri, path, parentDocId, docs, cues.first(),
                    rootDocs, warnings)?.let { discs += it }
                return // content-driven stop: no descent inside a disc folder
            }
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
            scanDiscFolder(treeUri, "", rootDocId, rootDocs, rootCues.first(),
                rootDocs, warnings)?.let { discs += it }
        }

        val compact = ArrayList<DiscScan>()
        for (d in discs) compact += d
        oversize = warnings.count { it.contains("oversize") }
        TreeScan(compact, jpegs, oversize, nonAudio, warnings)
    }

    private suspend fun scanDiscFolder(
        treeUri: Uri,
        path: String,
        docId: String,
        docs: List<SafDocument>,
        cue: SafDocument,
        rootDocs: List<SafDocument>,
        warnings: MutableList<String>,
    ): DiscScan? {
        val w = warnings
        if (cue.size > ScanPlan.MAX_READ_BYTES) {
            w += "$path: cue oversize ${cue.size}B — skipped"
            return null
        }
        val cueBytes = openRead(cue.uri)?.use { it.readBytes() }
            ?: run {
                w += "$path: cue unreadable"
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
        val wanted = sheet?.fileName
        val fileOk = wanted != null &&
            !wanted.contains('/') && !wanted.contains('\\') &&
            !wanted.contains("..")
        val flac = if (fileOk) {
            docs.firstOrNull {
                it.kind == SafDocumentKind.FLAC &&
                    it.displayName.equals(wanted, ignoreCase = true)
            } ?: run {
                w += "$path: FILE \"${sheet?.fileName}\" not a sibling document"
                null
            }
        } else {
            if (wanted != null) w += "$path: FILE \"$wanted\" escapes disc folder"
            null
        }
        val log = docs.firstOrNull { it.kind == SafDocumentKind.LOG }
        if (log != null && log.size > ScanPlan.MAX_READ_BYTES) {
            w += "$path: log oversize ${log.size}B"
        }
        val artwork = ArtworkResolver.resolve(docs, rootDocs)
        val flacDuration = flac?.let { f ->
            openRead(f.uri)?.use { FlacHeaderReader.readStreamInfo(it)?.durationMs }
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
