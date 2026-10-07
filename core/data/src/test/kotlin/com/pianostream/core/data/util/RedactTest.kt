package com.pianostream.core.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CC4/F13: the single redaction helper strips `pot|sig|n|lsig|expire|ip|cpn|range`
 * query-param values while leaving the URL shape (and unrelated params) intact.
 */
class RedactTest {

    @Test
    fun `strips every sensitive param`() {
        val url = "https://rr1---sn-abc.googlevideo.com/videoplayback?" +
            "pot=SECRET1&sig=SECRET2&n=SECRET3&lsig=SECRET4&expire=9999999999&" +
            "ip=1.2.3.4&cpn=SECRET5&range=0-100&id=keepme&itag=251"
        val out = Redact.url(url)

        for (name in listOf("pot", "sig", "n", "lsig", "expire", "ip", "cpn", "range")) {
            assertThat(out).contains("$name=[REDACTED]")
        }
        assertThat(out).doesNotContain("SECRET")
        assertThat(out).doesNotContain("1.2.3.4")
        assertThat(out).doesNotContain("9999999999")
        assertThat(out).doesNotContain("0-100")
        // Unrelated params survive.
        assertThat(out).contains("id=keepme")
        assertThat(out).contains("itag=251")
        assertThat(out).startsWith("https://rr1---sn-abc.googlevideo.com/videoplayback?")
    }

    @Test
    fun `param matching is exact not substring`() {
        val url = "https://x.test/v?signature=abc123&itag=140&lorem=ipsum"
        val out = Redact.url(url)
        // "signature" is NOT "sig" — survives (plan strips exact names only).
        assertThat(out).contains("signature=abc123")
        assertThat(out).contains("itag=140")
    }

    @Test
    fun `non url input passes through`() {
        assertThat(Redact.url("")).isEqualTo("")
        assertThat(Redact.url("no query here")).isEqualTo("no query here")
        assertThat(Redact.url("https://x.test/path")).isEqualTo("https://x.test/path")
        assertThat(Redact.url("https://x.test/path?")).isEqualTo("https://x.test/path?")
    }

    @Test
    fun `fragment is preserved`() {
        val out = Redact.url("https://x.test/v?sig=zzz&a=1#frag")
        assertThat(out).endsWith("#frag")
        assertThat(out).doesNotContain("zzz")
    }

    @Test
    fun `bare key without equals is not mangled`() {
        val out = Redact.url("https://x.test/v?sig&a=1")
        assertThat(out).contains("sig")
    }
}
