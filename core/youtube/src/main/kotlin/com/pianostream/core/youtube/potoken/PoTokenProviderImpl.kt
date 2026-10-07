package com.pianostream.core.youtube.potoken

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Minting seam — the WebView path implements this; tests inject fakes. */
internal interface PoTokenMinter {
    suspend fun mint(binding: PoTokenBinding, key: String): String?
}

internal class PoTokenProviderImpl(
    private val minter: PoTokenMinter,
    private val cache: PoTokenCache = PoTokenCache(),
) : PoTokenProvider {

    private val mintMutex = Mutex()

    override suspend fun token(binding: PoTokenBinding, key: String): String? {
        cache.get(binding, key)?.let { return it }
        // Single-flight per (binding,key): concurrent callers share one mint.
        return mintMutex.withLock {
            cache.get(binding, key) ?: minter.mint(binding, key)
                ?.also { cache.put(binding, key, it) }
        }
    }

    override fun cached(binding: PoTokenBinding, key: String): String? =
        cache.get(binding, key)

    /** CC10 — zero both caches on DELETE ALL LOCAL DATA. */
    override fun wipe() = cache.wipe()
}

/**
 * Real minter: binds [PoTokenHostService] in `:potoken`, sends `MSG_MINT`,
 * awaits `MSG_TOKEN` on a reply Messenger — hard-bounded at [MINT_TIMEOUT_MS]
 * (M8: mint ≤8 s). Lazily bound on first use; never at app start.
 */
internal class WebViewPoTokenMinter(
    private val context: Context,
    private val mintTimeoutMs: Long = MINT_TIMEOUT_MS,
) : PoTokenMinter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var service: Messenger? = null
    private val bindLock = Mutex()

    private suspend fun boundService(): Messenger? {
        service?.let { return it }
        return bindLock.withLock {
            service ?: bindOnce().also { service = it }
        }
    }

    private suspend fun bindOnce(): Messenger? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                cont.resume(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        val intent = android.content.Intent(context, PoTokenHostService::class.java)
        val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!ok) cont.resume(null)
    }

    override suspend fun mint(binding: PoTokenBinding, key: String): String? {
        val svc = boundService() ?: return null
        return withTimeoutOrNull(mintTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                val replyHandler = Handler(Looper.getMainLooper()) { msg ->
                    if (msg.what == PoTokenHostService.MSG_TOKEN) {
                        cont.resume(msg.data.getString("token"))
                        true
                    } else false
                }
                val request = Message.obtain().apply {
                    what = PoTokenHostService.MSG_MINT
                    replyTo = Messenger(replyHandler)
                    data = Bundle().apply {
                        putString("binding", binding.name)
                        putString("key", key)
                    }
                }
                scope.launch { runCatching { svc.send(request) } }
            }
        }
    }

    companion object {
        const val MINT_TIMEOUT_MS = 8_000L
    }
}
