package com.pianostream.core.youtube.potoken

import android.webkit.WebResourceRequest
import android.net.Uri
import android.webkit.WebView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * CC8/F3 lockdown assertions over a shadowed WebView (Robolectric): the four
 * access toggles off, JS on only because BotGuard needs it, the navigation
 * allow-list enforced, and the only bridge is write-only `submitToken`.
 */
@RunWith(RobolectricTestRunner::class)
class PoTokenWebViewLockdownTest {

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun `settings are locked down`() {
        val host = PoTokenWebView(RuntimeEnvironment.getApplication()) { _, _ -> }
        val s = host.webView.settings
        assertThat(s.allowFileAccess).isFalse()
        assertThat(s.allowContentAccess).isFalse()
        assertThat(s.allowUniversalAccessFromFileURLs).isFalse()
        assertThat(s.allowFileAccessFromFileURLs).isFalse()
        assertThat(s.domStorageEnabled).isFalse()
        assertThat(s.databaseEnabled).isFalse()
        assertThat(s.cacheMode).isEqualTo(android.webkit.WebSettings.LOAD_NO_CACHE)
        assertThat(s.mixedContentMode)
            .isEqualTo(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW)
    }

    @Test
    fun `navigation outside the allow-list is cancelled`() {
        val host = PoTokenWebView(RuntimeEnvironment.getApplication()) { _, _ -> }
        val client = shadowOf(host.webView).webViewClient
        assertThat(
            client.shouldOverrideUrlLoading(host.webView, request("https://evil.example.com/"))
        ).isTrue()
        assertThat(
            client.shouldOverrideUrlLoading(host.webView, request("https://www.youtube.com/embed/x"))
        ).isFalse()
        assertThat(
            client.shouldOverrideUrlLoading(host.webView, request("file:///etc/passwd"))
        ).isTrue()
        assertThat(
            client.shouldOverrideUrlLoading(host.webView, request("javascript:alert(1)"))
        ).isTrue()
    }

    @Test
    fun `only the write-only token bridge is exposed`() {
        val host = PoTokenWebView(RuntimeEnvironment.getApplication()) { _, _ -> }
        // Exactly one @JavascriptInterface method exists on the sole bridge.
        val methods = host.tokenBridge.javaClass.declaredMethods
            .filter { it.isAnnotationPresent(android.webkit.JavascriptInterface::class.java) }
        assertThat(methods.map { it.name }).containsExactly("submitToken")
    }

    @Test
    fun `bridge rejects bad binding and implausible tokens`() {
        var delivered: Pair<String, String>? = null
        val host = PoTokenWebView(RuntimeEnvironment.getApplication()) { b, t ->
            delivered = b to t
        }
        val submit = host.tokenBridge.javaClass.getDeclaredMethod(
            "submitToken", String::class.java, String::class.java,
        ).apply { isAccessible = true }
        val bridge = host.tokenBridge

        submit.invoke(bridge, "GVS", "tok_0123456789")
        assertThat(delivered).isEqualTo("GVS" to "tok_0123456789")

        delivered = null
        submit.invoke(bridge, "EVIL", "tok_0123456789")
        assertThat(delivered).isNull()

        submit.invoke(bridge, "VISITOR", "short")
        assertThat(delivered).isNull()

        submit.invoke(bridge, "VISITOR", "has space in token!!")
        assertThat(delivered).isNull()
    }
}
