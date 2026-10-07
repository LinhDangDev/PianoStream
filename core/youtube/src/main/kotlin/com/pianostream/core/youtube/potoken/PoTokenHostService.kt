package com.pianostream.core.youtube.potoken

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * Host component for the locked-down WebView, declared
 * `android:process=":potoken"` + `android:exported="false"` (CC8). The mint
 * happens entirely inside this process; callers in the app process bind and
 * exchange two Messenger ops:
 *
 *  - `MSG_MINT` (what=1): `obj` = Bundle{`binding`: String, `key`: String}
 *    with `replyTo` set to the caller's reply [Messenger].
 *  - `MSG_TOKEN` (what=2) reply: Bundle{`binding`: String, `key`: String,
 *    `token`: String}.
 *
 * The service also hosts [WebViewCipherSolver]'s eval requests via
 * `MSG_EVAL`/`MSG_EVAL_RESULT` — the *same* locked-down surface, so the cipher
 * fallback introduces no second unbounded WebView (CC8/F14).
 */
internal class PoTokenHostService : Service() {

    private var webView: PoTokenWebView? = null

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_MINT -> {
                val data = msg.data
                val binding = data.getString("binding") ?: return@Handler true
                val key = data.getString("key") ?: return@Handler true
                val replyTo = msg.replyTo ?: return@Handler true
                mintOnMain(binding, key, replyTo)
                true
            }
            MSG_EVAL -> {
                val data = msg.data
                val code = data.getString("code") ?: return@Handler true
                val replyTo = msg.replyTo ?: return@Handler true
                evalOnMain(code, replyTo)
                true
            }
            else -> false
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun mintOnMain(binding: String, key: String, replyTo: Messenger) {
        val bindingEnum = runCatching { PoTokenBinding.valueOf(binding) }
            .getOrNull() ?: return
        val view = webView ?: PoTokenWebView(this) { b, token ->
            // write-only bridge result → back to caller process
            val reply = Message.obtain().apply {
                what = MSG_TOKEN
                data = Bundle().apply {
                    putString("binding", b)
                    putString("key", key)
                    putString("token", token)
                }
            }
            runCatching { replyTo.send(reply) }
            webView?.destroy()
            webView = null
        }.also { webView = it }
        view.loadMintPage(bindingEnum, key)
    }

    private fun evalOnMain(code: String, replyTo: Messenger) {
        val view = webView ?: PoTokenWebView(this) { _, _ -> }.also { webView = it }
        view.evalForCipher(code) { result ->
            val reply = Message.obtain().apply {
                what = MSG_EVAL_RESULT
                data = Bundle().apply { putString("result", result) }
            }
            runCatching { replyTo.send(reply) }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val MSG_MINT = 1
        const val MSG_TOKEN = 2
        const val MSG_EVAL = 3
        const val MSG_EVAL_RESULT = 4
    }
}
