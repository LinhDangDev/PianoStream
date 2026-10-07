package com.pianostream.core.youtube.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The locked-down WebView that mints poTokens (CC8/F3) — and, because
 * `WebViewCipherSolver` reuses this same host, the *only* remote-JS surface in
 * the module.
 *
 * Lockdown contract (asserted by Robolectric test):
 *  - `android:process=":potoken"` host process (see AndroidManifest);
 *  - `setAllowFileAccess` / `setAllowContentAccess` /
 *    `setAllowUniversalAccessFromFileURLs` / `setDomStorageEnabled` = false;
 *  - SafeBrowsing enabled where supported;
 *  - navigation allow-list: `{https://www.youtube.com, https://music.youtube.com}`;
 *    every other navigation is cancelled;
 *  - exactly one bridge: write-only `submitToken(binding, token)` — validates
 *    shape/length, returns nothing;
 *  - warm lazily on first resolve (never at app start); after mint the host
 *    loads `about:blank` and destroys the view.
 */
internal class PoTokenWebView(
    context: Context,
    private val onToken: (binding: String, token: String) -> Unit,
) {

    val webView: WebView = createLockedDown(context)

    /** Kept as a field so tests can inspect the single exposed surface. */
    internal val tokenBridge = TokenBridge()

    /** Token shape: non-empty, sane length, no whitespace/control chars. */
    private fun isPlausibleToken(token: String): Boolean =
        token.length in 10..2048 && token.all { it.code in 33..126 }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    internal inner class TokenBridge {
        // Write-only: takes values in, returns nothing to JS.
        @JavascriptInterface
        fun submitToken(binding: String, token: String) {
            if (binding !in setOf("VISITOR", "GVS")) return
            if (!isPlausibleToken(token)) return
            onToken(binding, token)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createLockedDown(context: Context): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true // required to run the BotGuard program
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, true)
        }
        wv.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url?.toString() ?: return true
                return NAV_ALLOW_LIST.none { url.startsWith(it) }
            }
        }
        @SuppressLint("JavascriptInterface")
        wv.addJavascriptInterface(tokenBridge, "PianoStreamTokenBridge")
        return wv
    }

    fun loadMintPage(binding: PoTokenBinding, seed: String) {
        CoroutineScope(Dispatchers.Main).launch {
            webView.loadUrl(
                "file:///android_asset/po_token.html?b=${binding.name}&s=$seed"
            )
        }
    }

    /** Cipher-fallback eval: same host, same lockdown — the only remote-JS
     *  surface in the module (CC8). Runs the EJS-preprocessed code and returns
     *  its string result. */
    fun evalForCipher(code: String, onResult: (String?) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                webView.evaluateJavascript(code) { value ->
                    onResult(value?.let { unquoteJson(it) })
                }
            }.onFailure { onResult(null) }
        }
    }

    private fun unquoteJson(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"")
        } else raw

    /** Post-mint teardown per CC8: blank the page and destroy the view. */
    fun destroy() {
        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                webView.loadUrl("about:blank")
                webView.removeJavascriptInterface("PianoStreamTokenBridge")
                webView.destroy()
            }
        }
    }

    companion object {
        val NAV_ALLOW_LIST = listOf(
            "https://www.youtube.com",
            "https://music.youtube.com",
            "file:///android_asset/", // bundled mint page only
        )
    }
}
