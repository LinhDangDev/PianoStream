@file:UnstableApi
package com.pianostream.app.e2e

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * Phase-5a E2E support. No Hilt, no app internals beyond the frozen public
 * seams — tests talk to the app the way a real consumer does:
 *  - `appContext`     = the app's own process context (same package → the
 *    session's CC9 allow-list grants it commands).
 *  - `foreignContext` = the instrumentation package (`*.test`) → treated as a
 *    FOREIGN controller; connects but receives zero commands.
 *  - Room databases   = opened read-only by file path (black-box persistence
 *    checks; never through repository objects).
 *  - Settings         = read from the preferences file's bytes only (the
 *    datastore artifact isn't on the androidTest classpath, and Gradle files
 *    are frozen anyway — the freeze rule forbids adding a dep).
 *  - SAF              = real persisted grants only. The suite never fabricates
 *    grants; `assumeSafGrant()` skips cleanly on an unprepared device.
 */
object E2eSupport {

    val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Context whose packageName differs from the app — a "foreign" client. */
    val foreignContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    val playbackDbPath get() = appContext.getDatabasePath("playback.db").absolutePath
    val libraryDbPath get() = appContext.getDatabasePath("library.db").absolutePath

    private val settingsFile: File
        get() = File(
            appContext.filesDir,
            "datastore/pianostream.settings.preferences_pb",
        )
    private val ROOT_KEY_BYTES = "libraryRootTreeUri".toByteArray(Charsets.UTF_8)

    fun libraryRootTreeUriIsSet(): Boolean =
        settingsFile.exists() && settingsFile.readBytes().containsSequence(ROOT_KEY_BYTES)

    fun persistedTreeGrants(): List<Uri> =
        appContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }

    fun assumeSafGrant() {
        val hasGrant = persistedTreeGrants().isNotEmpty() || libraryRootTreeUriIsSet()
        Assume.assumeTrue(
            "E2E skipped: no persisted SAF tree grant on this device. " +
                "Prepare per docs/e2e/qa-matrix.md (install, pick folder once).",
            hasGrant,
        )
    }

    fun openDb(path: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    fun dbCount(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** Explicit marker wait — never a blind sleep. Throws AssertionError on timeout. */
    suspend fun awaitUntil(
        timeoutMs: Long,
        describe: String,
        pollMs: Long = 250,
        block: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            delay(pollMs)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $describe")
    }

    /**
     * Runs `block` on the instrumentation main thread. Every MediaController
     * call must land on its application looper (= main) — a plain
     * `runBlocking` body on the test thread throws
     * "called from a wrong thread".
     */
    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching(block)
        }
        return result!!.getOrThrow()
    }

    /**
     * Connect a MediaController from the given context. The builder runs on
     * main; the test thread only awaits the future's listener — never calls
     * `.get()` on main (that would deadlock: the connection callback
     * dispatches on the same looper).
     */
    fun connectController(context: Context): MediaController {
        val latch = java.util.concurrent.CountDownLatch(1)
        val controller = java.util.concurrent.atomic.AtomicReference<MediaController>()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        onMain {
            val token = SessionToken(
                appContext,
                android.content.ComponentName(
                    appContext.packageName,
                    "com.pianostream.core.media.PlaybackService",
                ),
            )
            val future = MediaController.Builder(context, token)
                .setApplicationLooper(android.os.Looper.getMainLooper())
                .buildAsync()
            future.addListener({
                runCatching { controller.set(future.get()) }
                    .onFailure { error.set(it) }
                latch.countDown()
            }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        error.get()?.let { throw it }
        return controller.get()
            ?: error("MediaController connect timed out")
    }

    fun playStreamUri(controller: MediaController, uri: String) = onMain {
        val item = MediaItem.Builder().setMediaId(uri).setUri(uri).build()
        controller.setMediaItem(item)
        controller.prepare()
        controller.play()
    }

    /** Decode-probe a local audio file (fixture sanity checks). */
    fun probeDurationMs(path: String): Long? = runCatching {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(path)
        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
    }.getOrNull()

    fun sdkAtLeast(v: Int) = Build.VERSION.SDK_INT >= v

    fun relaunchMainActivity() {
        val ctx = appContext
        val i = Intent(ctx, com.pianostream.app.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || size < needle.size) return false
    outer@ for (i in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}
