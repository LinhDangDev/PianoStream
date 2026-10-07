package com.pianostream.core.youtube.cipher

import com.pianostream.core.youtube.client.RegistryHosts
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Player-config registry store (F15 + F2/U3).
 *
 *  - **Gate:** reads `remoteConfigEnabled` via the injected [enabledProvider];
 *    when false the store serves only the bundled seed + any previously
 *    accepted `filesDir/player_configs.json` and emits **zero** requests.
 *    An unknown player hash with no local entry fails closed — callers map it
 *    to `ExtractionException.AllClientsFailed`.
 *  - **Hosts:** fetches only from [RegistryHosts.allowed] over https.
 *  - **Validation:** [RemoteConfigValidator] schema allow-list + size cap;
 *    rejected payloads never touch the cache.
 *  - **Cache:** `If-None-Match` (ETag), 6–24 h TTL, single-flight, accepted
 *    payload persisted at `filesDir/player_configs.json` (wiped by
 *    DELETE ALL LOCAL DATA, CC10).
 *  - The active host is exposed via [activeHost] so the force-refresh dialog
 *    can name it (F2/U3).
 */
internal class RemotePlayerConfigStore(
    private val seedJson: ByteArray,
    private val filesDir: File,
    private val enabledProvider: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val registryUrls: List<String> = RegistryHosts.candidateUrls,
    private val minTtlMs: Long = MIN_TTL_MS,
    private val maxTtlMs: Long = MAX_TTL_MS,
    /** Host allow-list check — injectable for MockWebServer tests (which run
     *  over http://localhost); production keeps [RegistryHosts.isAllowed]. */
    private val allowCheck: (String) -> Boolean = RegistryHosts::isAllowed,
) {

    private val file = File(filesDir, "player_configs.json")
    private val refreshMutex = Mutex()

    @Volatile private var registry: RemoteConfigValidator.Registry? = null
    @Volatile private var etag: String? = null
    @Volatile private var lastFetchAtMs: Long = 0L
    @Volatile var activeHost: String? = null
        private set
    @Volatile var lastRefreshAtMs: Long = 0L
        private set

    /** Config for [playerHash]: local seed/cache first; a remote refresh runs
     *  only when enabled AND the hash is unknown OR the entry is stale. */
    suspend fun configFor(playerHash: String, forceRefresh: Boolean = false): PlayerConfig? {
        localConfig(playerHash)?.let { if (!forceRefresh) return it }
        if (!enabledProvider()) return localConfig(playerHash)
        refresh(force = forceRefresh || localConfig(playerHash) == null)
        return localConfig(playerHash)
    }

    /** Warm the in-memory registry from seed + accepted cache (no network). */
    fun warmLocal() {
        if (registry != null) return
        registry = loadPersisted() ?: RemoteConfigValidator.validate(seedJson)
    }

    private fun localConfig(playerHash: String): PlayerConfig? {
        warmLocal()
        return registry?.configs?.get(playerHash)
    }

    private fun loadPersisted(): RemoteConfigValidator.Registry? =
        runCatching {
            file.takeIf { it.isFile }?.readBytes()
                ?.let(RemoteConfigValidator::validate)
        }.getOrNull()

    /**
     * Single-flight refresh: concurrent callers coalesce on [refreshMutex];
     * a second caller that arrives while one refresh is in flight returns
     * without an extra request. TTL and ETag short-circuit inside.
     */
    suspend fun refresh(force: Boolean = false) {
        if (!enabledProvider()) return
        if (refreshMutex.isLocked && !force) return
        val waitedForInFlight = refreshMutex.isLocked
        refreshMutex.withLock {
            val now = nowMs()
            // A waiter satisfied by the refresh that just landed must not fire
            // a second request — single-flight means one HTTP request total.
            if (waitedForInFlight && now - lastFetchAtMs < COALESCE_MS) return
            if (!force && now - lastFetchAtMs < minTtlMs) return
            for (url in registryUrls) {
                if (fetchOne(url, now)) break
            }
        }
    }

    private suspend fun fetchOne(url: String, now: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!allowCheck(url)) return@withContext false
            val req = Request.Builder().url(url).apply {
                etag?.let { header("If-None-Match", it) }
            }.build()
            try {
                okHttp.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 304 -> {
                            lastFetchAtMs = now
                            true
                        }
                        !resp.isSuccessful -> false
                        else -> {
                            val bytes = resp.body?.bytes() ?: return@withContext false
                            if (bytes.size > RemoteConfigValidator.MAX_BYTES) {
                                return@withContext false
                            }
                            val parsed = RemoteConfigValidator.validate(bytes)
                                ?: return@withContext false // fail closed
                            registry = parsed
                            etag = resp.header("ETag")
                            activeHost = runCatching { java.net.URI(url).host }.getOrNull()
                            lastFetchAtMs = now
                            lastRefreshAtMs = now
                            runCatching { file.writeBytes(bytes) }
                            true
                        }
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

    /** CC10 hook: drop persisted + in-memory state (seed still applies). */
    fun wipe() {
        runCatching { file.delete() }
        registry = null
        etag = null
        lastFetchAtMs = 0
        lastRefreshAtMs = 0
        activeHost = null
    }

    companion object {
        const val MIN_TTL_MS = 6 * 60 * 60 * 1000L
        const val MAX_TTL_MS = 24 * 60 * 60 * 1000L
        /** Concurrency coalescing window inside the refresh lock. */
        const val COALESCE_MS = 1_000L
    }
}
