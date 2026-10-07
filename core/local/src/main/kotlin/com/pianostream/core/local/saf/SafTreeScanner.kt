package com.pianostream.core.local.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Children-of-a-folder seam — the real impl issues one
 * `DocumentsContract.buildChildDocumentsUriUsingTree` cursor per folder
 * (never `DocumentFile`: one binder IPC per metadata call, M10/F20); tests
 * substitute an in-memory tree.
 */
interface SafDocumentSource {
    suspend fun children(treeUri: Uri, parentDocId: String): List<SafDocument>
}

/** ContentResolver-backed [SafDocumentSource]. */
class ContentResolverSafSource(
    private val resolver: ContentResolver,
) : SafDocumentSource {

    override suspend fun children(treeUri: Uri, parentDocId: String): List<SafDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, parentDocId,
        )
        val out = ArrayList<SafDocument>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val docId = c.getString(0)
                out += SafDocument(
                    docId = docId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, docId,
                    ).toString(),
                    displayName = name,
                    mimeType = mime,
                    size = c.getLong(3),
                    lastModified = c.getLong(4),
                    kind = classify(name, mime),
                )
            }
        }
        return out
    }

    companion object {
        /** MIME/extension agreement classification (F20 — disagreement → OTHER). */
        fun classify(displayName: String, mimeType: String): SafDocumentKind {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val mime = mimeType.lowercase()
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                return SafDocumentKind.FOLDER
            }
            return when (ext) {
                "cue" -> if (mime.startsWith("text/") || mime == "application/octet-stream" ||
                    mime.contains("cue") || mime.isEmpty()
                ) SafDocumentKind.CUE else SafDocumentKind.OTHER
                "flac" -> if (mime.startsWith("audio/") || mime == "application/octet-stream" ||
                    mime.isEmpty()
                ) SafDocumentKind.FLAC else SafDocumentKind.OTHER
                "log" -> if (mime.startsWith("text/") || mime == "application/octet-stream" ||
                    mime.isEmpty()
                ) SafDocumentKind.LOG else SafDocumentKind.OTHER
                "jpeg", "jpg" -> if (mime.startsWith("image/") || mime.isEmpty()) {
                    SafDocumentKind.JPEG
                } else {
                    SafDocumentKind.OTHER
                }
                "pdf", "exe", "inf", "txt" -> SafDocumentKind.PRUNED
                else -> SafDocumentKind.OTHER
            }
        }
    }
}
