package com.pianostream.core.local.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF tree-grant handling (F8/M2):
 *  - picker intent requests `READ | PERSISTABLE` only — never `WRITE`;
 *  - [takePersisted] asserts `PERSISTABLE` is in the result flags, takes only
 *    the flags actually granted, and asserts `WRITE` is never taken;
 *  - authority allow-list gates foreign `DocumentsProvider`s;
 *  - [isVolumeRoot] feeds the "whole volume" warning on the picker confirm.
 */
object SafPermissions {

    val ALLOWED_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.google.android.providers.media.documents",
    )

    fun buildPickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    sealed class TakeResult {
        data class Granted(val treeUri: Uri, val readOnly: Boolean) : TakeResult()
        /** Persistable flag absent — the provider cannot grant durability. */
        object NotPersistable : TakeResult()
        /** Provider authority outside the allow-list (F8). */
        data class UnsupportedProvider(val authority: String?) : TakeResult()
    }

    /**
     * Takes the persistable grant from an `ACTION_OPEN_DOCUMENT_TREE` result.
     * Uses only `resultIntent.flags and (READ or WRITE)`; `WRITE` is asserted
     * never taken even if the provider returns it.
     */
    fun takePersisted(
        resolver: ContentResolver,
        resultIntent: Intent,
    ): TakeResult {
        val tree = resultIntent.data ?: return TakeResult.NotPersistable
        val flags = resultIntent.flags
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            return TakeResult.NotPersistable
        }
        val authority = tree.authority
        if (authority !in ALLOWED_AUTHORITIES) {
            return TakeResult.UnsupportedProvider(authority)
        }
        val takeFlags = flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(takeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            "WRITE grant must never be taken (F8)"
        }
        resolver.takePersistableUriPermission(tree, takeFlags)
        return TakeResult.Granted(
            treeUri = tree,
            readOnly = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0,
        )
    }

    /** Human-readable path for the picker confirmation (F8 warning text). */
    fun resolvedDisplayPath(treeUri: Uri): String =
        runCatching {
            DocumentsContract.getTreeDocumentId(treeUri).replace(':', '/')
        }.getOrDefault(treeUri.toString())

    /** `primary:` or an empty document id ⇒ the user picked a whole volume. */
    fun isVolumeRoot(treeUri: Uri): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrDefault("")
            .let { it.isEmpty() || it.endsWith(':') || it.split(':').last().isEmpty() }

    /** Cold-start health check: is the persisted grant still usable? (M2). */
    fun isTreeReadable(resolver: ContentResolver, treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    fun revoke(resolver: ContentResolver, treeUri: Uri) {
        resolver.releasePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
