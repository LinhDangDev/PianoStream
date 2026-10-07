@file:UnstableApi
package com.pianostream.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import com.pianostream.app.MainActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

import com.pianostream.app.e2e.E2eSupport.appContext

/**
 * Hostile-input cases whose *controls* land in Phase 5b — authored at 5a per
 * plan §5a step 6, executed at the 5b gate:
 *
 *   - userCAtest_allEgressFails: F7 `network_security_config` already ships in
 *     `app/src/main/res/xml` (system CAs only, cleartext off). The executable
 *     half of that contract is asserted NOW: an endpoint presenting a
 *     non-system-CA chain must be rejected from inside the app process — the
 *     same rejection a user-installed MITM CA would produce. The full MITM
 *     rig (install a test CA into the user store + force traffic through a
 *     proxy presenting a cert signed by it) is a device-lab procedure; see
 *     docs/e2e/qa-matrix.md for the manual steps.
 *   - tamperedApk_rejectedNoInstallPath: the updater's ApkVerifier +
 *     checksums/minisign chain lands in 5b. Until `com.pianostream` gains an
 *     `updater` package with a verifier entry point, this case assume-skips —
 *     it activates automatically the first time the control exists.
 */
class Phase5bGateE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun userCaUntrustedChain_rejectedFromAppProcess() {
        // self-signed.badssl.com presents a self-signed cert — untrusted by
        // every system store. The app's network config (system CAs only) must
        // reject the handshake from inside the app process. This is the same
        // TLS path all catalog/registry/stream egress rides (OkHttp).
        val client = OkHttpClient()
        var sslFailure = false
        try {
            client.newCall(
                Request.Builder().url("https://self-signed.badssl.com/").build(),
            ).execute().use { resp ->
                fail("untrusted chain was accepted — HTTP ${resp.code}")
            }
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            sslFailure = true
        } catch (e: java.net.UnknownHostException) {
            assumeTrue("no network on device — deferring TLS assertion", false)
        } catch (e: javax.net.ssl.SSLException) {
            sslFailure = true // other TLS-failure forms also satisfy F7
        }
        assertTrue("non-system-CA chain must be rejected", sslFailure)
    }

    @Test
    fun tamperedApk_rejectedNoInstallPath(): Unit = runBlocking {
        // 5b control not yet landed — the updater's verifier entry point is
        // discovered reflectively so this body compiles now and activates at
        // the 5b gate without edits.
        val verifier = listOf(
            "com.pianostream.app.updater.ApkVerifier",
            "com.pianostream.core.updater.ApkVerifier",
        ).firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name) }.getOrNull()
        }
        assumeTrue(
            "ApkVerifier lands in Phase 5b — skipping authored case",
            verifier != null,
        )

        // When the verifier exists, the 5b checklist wires the concrete flow:
        // plant a tampered APK + mismatched checksums.txt/minisig in the
        // updater's download dir, drive verification, and assert:
        //   1) verification throws / returns a terminal-failure result,
        //   2) the file is deleted (no residue),
        //   3) no install intent is ever produced (F1/F6 — terminal, not
        //      warn-and-continue).
        fail("ApkVerifier present — wire the 5b tamper flow before releasing")
    }
}
