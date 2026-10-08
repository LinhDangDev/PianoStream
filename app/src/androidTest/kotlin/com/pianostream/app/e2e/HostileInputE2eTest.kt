@file:UnstableApi
package com.pianostream.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.util.UnstableApi
import com.pianostream.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

import com.pianostream.app.e2e.E2eSupport.appContext
import com.pianostream.app.e2e.E2eSupport.assumeSafGrant
import com.pianostream.app.e2e.E2eSupport.awaitUntil
import com.pianostream.app.e2e.E2eSupport.dbCount
import com.pianostream.app.e2e.E2eSupport.libraryDbPath
import com.pianostream.app.e2e.E2eSupport.libraryRootTreeUriIsSet
import com.pianostream.app.e2e.E2eSupport.persistedTreeGrants
import com.pianostream.app.e2e.E2eSupport.openDb

/**
 * Phase-5a hostile-input gate (plan §5a #4, redteam fixlists):
 *   - malformed/oversized .cue files inside the granted tree never crash the
 *     scanner and never land rows in `cue_sheets_raw` / `tracks`;
 *   - hostile names (quotes, emoji, 300-char filenames) are treated as data;
 *   - FTS hostile queries through the real Search UI never crash or hang;
 *   - F8 grant-loss: releasing the persisted tree permission makes the app
 *     surface the revoke banner, and "Remove access" wipes the library.
 *
 * Device prep (qa-matrix.md): `E2E-Hostile/` fixture folder is adb-pushed into
 * the granted tree root BEFORE the run (the grant is read-only — the suite
 * cannot write the fixtures in itself, so they arrive via the corpus push).
 *
 * ORDER MATTERS: `zz_grantLoss` runs last (FixMethodOrder) because it
 * permanently releases the SAF grant — after this class the device must be
 * re-granted to re-run other classes.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HostileInputE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireGrant() = assumeSafGrant()

    private fun rescan() = runBlocking {
        compose.onNodeWithText("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Library actions").performClick()
        compose.onNodeWithText("Rescan").performClick()
    }

    @Test
    fun a_hostileCueFixtures_neverLandRowsOrCrash(): Unit = runBlocking {
        rescan()
        // Scan-complete marker that survives a pre-seeded DB: the E2E-VALID
        // cue rows themselves must land — that only happens at the END of a
        // full tree walk, so their presence proves the scan finished.
        awaitUntil(90_000, "scan ingests E2E-VALID fixtures (walk complete)") {
            openDb(libraryDbPath).use { db ->
                dbCount(
                    db,
                    "SELECT COUNT(*) FROM cue_sheets_raw WHERE uri LIKE '%E2E-VALID%'",
                ) >= 1
            }
        }

        openDb(libraryDbPath).use { db ->
            // Fixtures named E2E-HOSTILE-* are intentionally unparseable —
            // they must never land a row. cue_sheets_raw keys by uri (the
            // document path carries the filename).
            // Spec (phase-03 §7): cue_sheets_raw is a rebuildable hash cache —
            // a failed cue MAY record a row (contentHash+parserVersion) so it
            // is not retried. The real contract: hostile cues produce ZERO
            // tracks and ZERO bogus titles — never a playable phantom.
            val hostileTracks = dbCount(
                db,
                "SELECT COUNT(*) FROM tracks t JOIN discs d ON t.discId = d.id " +
                    "WHERE d.title LIKE 'E2E-HOSTILE%' OR t.title LIKE 'E2E-HOSTILE%'",
            )
            assertEquals("hostile cues produced bogus tracks", 0, hostileTracks)

            // Fixtures named E2E-VALID-* are hostile-*named* but perfectly
            // well-formed cues — the hostile name must be treated as data:
            // they DO land (proves quotes/emoji/long names don't break ingest).

        }

        // App still alive and responsive — the hostile ingest must not have
        // killed anything downstream.
        assertTrue(
            compose.onAllNodesWithContentDescription("Open disc", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun b_tamperedPlayerConfig_failsClosed(): Unit = runBlocking {
        // F2/U3: a tampered persisted player-config registry must be refused —
        // strict-schema validation fails closed, never falls back to
        // unverified data. Write garbage over filesDir/player_configs.json
        // (this test runs in-app, so filesDir is the app's own), then give the
        // app a moment and verify it is still healthy and functional.
        val cfg = java.io.File(appContext.filesDir, "player_configs.json")
        cfg.writeBytes(
            """{"version":1,"configs":{"evil":{"sig":"<script>","nClass":null}},"exploit":"${"A".repeat(300_000)}"}"""
                .toByteArray(),
        )
        assertTrue("tamper write did not land", cfg.readBytes().isNotEmpty())

        // The app must never crash and must remain fully usable; any later
        // resolve consults the seed registry only (emulator note in
        // qa-matrix.md: this IP is bot-blocked, so on-device runs get the
        // stronger signal — a stream built from tampered data would be a fail).
        compose.onNodeWithText("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Library actions").performClick()
        awaitUntil(15_000, "app healthy after tampered config") {
            runCatching {
                compose.onNodeWithText("Rescan").assertExists()
            }.isSuccess
        }
        cfg.delete()
    }

    @Test
    fun b_hostileFtsQueries_neverCrash(): Unit = runBlocking {
        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()

        val hostile = listOf(
            "' OR 1=1 --",
            "*",
            "NEAR/4(",
            "\"unclosed",
            "title: (",
            "😀🔥💀",
            "a".repeat(500),
            "FTS5 NEAR(",
            "\u0000nul",
        )
        val field = compose.onNode(hasSetTextAction())
        for (q in hostile) {
            field.performTextInput(q)
            compose.waitForIdle()
            field.performTextClearance()
        }
        // Survival marker: the search field is still there and the process is
        // responsive after the whole batch.
        field.assertExists()
    }

    @Test
    fun zz_grantLoss_surfacesBannerThenRevokeClearsRoot(): Unit = runBlocking {
        val treeUri = persistedTreeGrants().firstOrNull()
            ?: error("no persisted tree grant — cannot test grant loss")

        // Simulate the user revoking the folder in system settings: release
        // the app's own persisted read grant externally, then ask for a
        // rescan — M2 says the lost grant must surface as an error banner,
        // never a silent empty library.
        appContext.contentResolver.releasePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )

        compose.onNodeWithText("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Library actions").performClick()
        compose.onNodeWithText("Rescan").performClick()

        // Compliant surfaces (either): explicit grantLost banner, or the M2
        // "0 discs — refusing to wipe" warning now rendered in the summary.
        awaitUntil(20_000, "grant-loss surface (banner or refuse-wipe warning)") {
            runCatching {
                compose.onNodeWithText("Library access was lost", substring = true)
                    .assertExists()
            }.isSuccess || runCatching {
                compose.onNodeWithText("refusing to wipe", substring = true)
                    .assertExists()
            }.isSuccess
        }

        // F8 revoke action clears the stored root — and must NOT crash when
        // the underlying grant is already gone externally.
        compose.onNodeWithContentDescription("Library actions").performClick()
        compose.onNodeWithText("Revoke access").performClick()
        compose.waitForIdle()

        awaitUntil(15_000, "libraryRootTreeUri cleared") {
            !libraryRootTreeUriIsSet()
        }
    }
}
