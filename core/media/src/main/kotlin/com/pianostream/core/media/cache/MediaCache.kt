package com.pianostream.core.media.cache

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache.CacheException
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-singleton `SimpleCache` under `cacheDir/media3` (E1/M4/M5).
 *
 *  - Budget comes from `cacheBudgetBytes` (default 512 MiB, clamped to the
 *    256 MiB–4 GiB range E1 allows).
 *  - `getCacheSpace()` is exposed for the Settings row.
 *  - `clearStreamCache()` (CC5/F18) evicts every key.
 *  - On `CacheException`/init failure the corrupt store is deleted and
 *    recreated exactly once, logged as a "cache reset" event (M4) — never a
 *    silent fallback to an uncached path.
 */
object MediaCache {

    private const val TAG = "MediaCache"
    private const val DIR = "media3"

    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var resetCount = 0

    /** Bytes currently in use inside the cache (for the Settings row). */
    val cacheSpaceBytes: Long
        get() = instance?.cacheSpace ?: 0L

    /** Times a corrupt cache had to be wiped and recreated (M4). */
    val resetEvents: Int get() = resetCount

    @Synchronized
    fun getInstance(context: Context, budgetBytes: Long): SimpleCache {
        instance?.let { return it }
        val dir = File(context.cacheDir, DIR)
        val provider = StandaloneDatabaseProvider(context)
        val clamped = budgetBytes.coerceIn(MIN_BUDGET, MAX_BUDGET)
        return try {
            build(dir, provider, clamped).also { instance = it }
        } catch (e: CacheException) {
            resetCount++
            Log.w(TAG, "cache reset: SimpleCache init failed; recreating", e)
            // M4: wipe the corrupt store, recreate, retry once.
            runCatching { SimpleCache.delete(dir, provider) }
            build(dir, provider, clamped).also { instance = it }
        }
    }

    private fun build(
        dir: File,
        provider: StandaloneDatabaseProvider,
        budgetBytes: Long,
    ): SimpleCache = SimpleCache(
        dir,
        LeastRecentlyUsedCacheEvictor(budgetBytes),
        provider,
    )

    /** CC5/F18 — evict every cached span (streaming byte cache). */
    @Synchronized
    fun clearStreamCache() {
        val c = instance ?: return
        // Remove every key the index knows about.
        c.keys.toList().forEach { c.removeResource(it) }
    }

    /** M4 pre-write guard: enough allocatable bytes for the write? */
    fun hasSpaceFor(context: Context, bytesNeeded: Long): Boolean =
        if (Build.VERSION.SDK_INT >= 26) hasSpaceApi26(context, bytesNeeded)
        else context.cacheDir.usableSpace > bytesNeeded

    @RequiresApi(26)
    private fun hasSpaceApi26(context: Context, bytes: Long): Boolean {
        val sm = context.getSystemService(StorageManager::class.java) ?: return true
        val uuid = sm.getUuidForPath(context.cacheDir)
        return runCatching { sm.getAllocatableBytes(uuid) >= bytes }.getOrDefault(true)
    }

    /** Test hook — drops the singleton (never called by the app). */
    @Synchronized
    internal fun releaseForTest() {
        runCatching { instance?.release() }
        instance = null
    }

    private const val MIN_BUDGET = 256L * 1024L * 1024L
    private const val MAX_BUDGET = 4L * 1024L * 1024L * 1024L
}
