package com.pianostream.core.youtube.cipher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.potoken.PoTokenHostService
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * CipherSolver fallback (R14): evaluates the same preprocessed player code
 * **inside the same locked-down `:potoken` WebView host** as the poToken
 * minter — identical CC8 process/settings surface, and the same F14 bounds:
 * a [deadlineMs] watchdog (default 250 ms) that abandons the eval and raises
 * [ExtractionException.CipherFailure], plus the engine-level page (no DOM
 * storage, no file/content access, navigation allow-list) as the effective
 * memory ceiling — eval payloads are additionally size-capped at
 * [MAX_EVAL_BYTES] so a giant script can't grow the renderer.
 */
internal class WebViewCipherSolver(
    private val context: Context,
    private val solverDispatcher: CoroutineDispatcher,
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    private val maxEvalBytes: Int = MAX_EVAL_BYTES,
    private val binder: suspend () -> Messenger? = { null },
) : CipherSolver {

    private val bindLock = Mutex()
    @Volatile private var service: Messenger? = null

    private suspend fun service(): Messenger? {
        service?.let { return it }
        return bindLock.withLock {
            service ?: (binder() ?: bindDefault()).also { service = it }
        }
    }

    private suspend fun bindDefault(): Messenger? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, b: IBinder) {
                cont.resume(Messenger(b))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        val ok = context.bindService(
            Intent(context, PoTokenHostService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
        if (!ok) cont.resume(null)
    }

    override suspend fun solve(request: SolveRequest): SolvedCipher {
        if (request.playerJs.toByteArray().size > maxEvalBytes) {
            throw ExtractionException.CipherFailure("player js exceeds eval cap")
        }
        return withContext(solverDispatcher) {
            val svc = service()
                ?: throw ExtractionException.CipherFailure("potoken host unavailable")
            val code = buildEvalScript(request)
            val raw = withTimeoutOrNull(deadlineMs) { evalOnHost(svc, code) }
                ?: throw ExtractionException.CipherFailure(
                    "webview eval exceeded ${deadlineMs}ms"
                )
            parseResult(raw, request)
        }
    }

    private fun buildEvalScript(request: SolveRequest): String {
        val b = StringBuilder()
        b.append("(function(){")
        b.append(request.playerJs)
        b.append(";var __o={};")
        request.signatureCipher?.let {
            b.append("__o.s=")
            b.append(request.config.sig)
            b.append("(")
            b.append(jsString(it))
            b.append(");")
        }
        request.nCipherInput?.let {
            b.append("__o.n=")
            b.append(request.config.nClass)
            b.append("(")
            b.append(jsString(it))
            b.append(");")
        }
        b.append("return JSON.stringify(__o);})()")
        return b.toString()
    }

    private fun jsString(s: String): String =
        '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

    private suspend fun evalOnHost(svc: Messenger, code: String): String? =
        suspendCancellableCoroutine { cont ->
            val reply = Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == PoTokenHostService.MSG_EVAL_RESULT) {
                    cont.resume(msg.data.getString("result"))
                    true
                } else false
            }
            val req = Message.obtain().apply {
                what = PoTokenHostService.MSG_EVAL
                replyTo = Messenger(reply)
                data = Bundle().apply { putString("code", code) }
            }
            runCatching { svc.send(req) }.onFailure { cont.resume(null) }
        }

    private fun parseResult(raw: String?, request: SolveRequest): SolvedCipher {
        if (raw == null) throw ExtractionException.CipherFailure("null eval result")
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: throw ExtractionException.CipherFailure("bad eval result")
        fun field(k: String) = obj[k]?.let {
            it as? kotlinx.serialization.json.JsonPrimitive
        }?.contentOrNull
        return SolvedCipher(
            signature = request.signatureCipher?.let { field("s") },
            n = request.nCipherInput?.let { field("n") },
        )
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 250L
        const val MAX_EVAL_BYTES = 4 * 1024 * 1024
    }
}
