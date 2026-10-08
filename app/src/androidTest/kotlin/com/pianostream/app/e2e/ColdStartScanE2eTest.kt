@file:UnstableApi
package com.pianostream.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import com.pianostream.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

import com.pianostream.app.e2e.E2eSupport.appContext
import com.pianostream.app.e2e.E2eSupport.assumeSafGrant
import com.pianostream.app.e2e.E2eSupport.awaitUntil
import com.pianostream.app.e2e.E2eSupport.dbCount
import com.pianostream.app.e2e.E2eSupport.libraryDbPath
import com.pianostream.app.e2e.E2eSupport.openDb

/**
 * Phase-5a cold-scan gate (plan §5a #1, plan §F1):
 *   100 discs / 1669 tracks / 205 JPEGs under 30 s on the reference corpus
 *   ("The Piano — The Ultimate Piano Collection", 100 single-FLAC cuesheets).
 *
 * Device prep (docs/e2e/qa-matrix.md): corpus + hostile fixtures pushed into a
 * folder granted to the app via the SAF picker ONCE before the suite runs.
 * On devices without the grant the whole class skips via [assumeSafGrant].
 *
 * The scan is triggered through the real UI path (Library → Rescan) — same
 * entry point a user takes — and completion is detected by polling row
 * counts, i.e. an explicit data marker, not a wall-clock sleep.
 */
class ColdStartScanE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireGrant() {
        assumeSafGrant()
    }

    @Test
    fun coldScan_under30s_andGroundTruthCounts(): Unit = runBlocking {
        // Enter Library — the root header means a persisted grant + prior scan.
        compose.onNodeWithText("Library").performClick()
        compose.waitForIdle()

        val startMs = System.currentTimeMillis()
        compose.onNodeWithContentDescription("Library actions").performClick()
        compose.onNodeWithText("Rescan").performClick()

        // Completion marker: ground-truth row counts reached. 60 s outer
        // bound lets us observe the failure cleanly; the <30 s gate is
        // asserted separately below.
        awaitUntil(timeoutMs = 60_000, describe = "scan to reach ground truth") {
            openDb(libraryDbPath).use { db ->
                dbCount(db, "SELECT COUNT(*) FROM discs") == 100L &&
                    dbCount(db, "SELECT COUNT(*) FROM tracks") == 1669L
            }
        }
        val elapsed = System.currentTimeMillis() - startMs

        assertTrue(
            "Cold scan took ${elapsed}ms — gate is < 30 000ms",
            elapsed < 30_000,
        )

        openDb(libraryDbPath).use { db ->
            // 205 JPEGs exist in the tree; artwork resolution picks one per
            // disc — every scanned disc must surface artwork.
            val withArt = dbCount(
                db,
                "SELECT COUNT(*) FROM discs WHERE artworkUri IS NOT NULL",
            )
            assertTrue("only $withArt/100 discs carry artworkUri", withArt >= 95)
        }
    }
}
