@file:UnstableApi
package com.pianostream.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import com.pianostream.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

import com.pianostream.app.e2e.E2eSupport.appContext
import com.pianostream.app.e2e.E2eSupport.assumeSafGrant
import com.pianostream.app.e2e.E2eSupport.awaitUntil
import com.pianostream.app.e2e.E2eSupport.connectController
import com.pianostream.app.e2e.E2eSupport.dbCount
import com.pianostream.app.e2e.E2eSupport.libraryDbPath
import com.pianostream.app.e2e.E2eSupport.playbackDbPath
import com.pianostream.app.e2e.E2eSupport.onMain
import com.pianostream.app.e2e.E2eSupport.openDb

/**
 * Phase-5a queue-mutation durability gate (plan §M9, §B9, §CC1):
 *   local queue ops write `queue_items` synchronously (per-track, never
 *   replace-all), and the durable queue is the cold-start restore source —
 *   cursor + order must survive release/reconnect.
 *
 * Exercise path is the real UI: scan → open disc → tap track → open queue →
 * remove row 0. Verification is black-box: `queue_items` is read straight
 * from playback.db, and post-"kill" restore is checked through a released +
 * reconnected MediaController (in-process proxy for cold start).
 *
 * The ±100 ms gapless number needs PCM-level analysis — covered by the
 * host-side timing check documented in docs/e2e/qa-matrix.md, not here.
 */
class QueueReorderResumeE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireGrant() = assumeSafGrant()

    private fun rescanAndWait() = runBlocking {
        compose.onNodeWithText("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rescan").performClick()
        awaitUntil(60_000, "at least one disc scanned") {
            openDb(libraryDbPath).use { db ->
                dbCount(db, "SELECT COUNT(*) FROM discs") >= 1
            }
        }
    }

    @Test
    fun removeFromQueue_persistsToDbAndSurvivesReconnect(): Unit = runBlocking {
        rescanAndWait()

        // Open the first disc → Tracks → tap first track (C6 path).
        awaitUntil(15_000, "disc row visible") {
            runCatching {
                compose.onAllNodesWithContentDescription("Open disc", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onAllNodesWithContentDescription("Open disc", substring = true)
            .onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tracks", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Play track", substring = true)
            .onFirst().performClick()

        awaitUntil(15_000, "queue_items persisted") {
            openDb(playbackDbPath).use { db ->
                dbCount(db, "SELECT COUNT(*) FROM queue_items") >= 2
            }
        }

        val beforeCount = openDb(playbackDbPath).use { db ->
            dbCount(db, "SELECT COUNT(*) FROM queue_items")
        }
        val secondMediaId = openDb(playbackDbPath).use { db ->
            db.rawQuery("SELECT mediaId FROM queue_items ORDER BY position ASC", null).use { c ->
                c.moveToPosition(1); c.getString(0)
            }
        }

        // Remove row 0 through the queue UI → must mirror into queue_items
        // (the B9 durable-queue contract this E2E exists to prove).
        compose.onNodeWithContentDescription("Open play queue").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Remove from queue")
            .onFirst().performClick()

        awaitUntil(15_000, "queue_items shrank") {
            openDb(playbackDbPath).use { db ->
                dbCount(db, "SELECT COUNT(*) FROM queue_items") == beforeCount - 1
            }
        }

        openDb(playbackDbPath).use { db ->
            val firstAfter = db.rawQuery(
                "SELECT mediaId FROM queue_items ORDER BY position ASC", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("durable queue kept stale head after remove", secondMediaId, firstAfter)
        }

        // Resume: reconnect → restore must rebuild from queue_items in the
        // post-remove order (release/reconnect ≈ cold start at session layer).
        val controller = connectController(appContext)
        try {
            awaitUntil(15_000, "restored queue") {
                onMain { controller.mediaItemCount }.toLong() == beforeCount - 1
            }
            onMain { assertEquals(secondMediaId, controller.getMediaItemAt(0).mediaId) }
        } finally {
            onMain { runCatching { controller.release() } }
        }
    }
}
