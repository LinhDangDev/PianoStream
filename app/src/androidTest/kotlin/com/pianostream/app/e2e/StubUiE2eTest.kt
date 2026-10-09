@file:UnstableApi
package com.pianostream.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pianostream.BuildConfig
import com.pianostream.app.MainActivity
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.media.api.PlaybackController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import com.pianostream.app.e2e.E2eSupport.appContext
import com.pianostream.app.e2e.E2eSupport.awaitUntil
import com.pianostream.app.e2e.E2eSupport.onMain

/**
 * Stub-flavor UI E2E (the `src/stub` seam): the app's in-process fakes
 * (FakePlayer, Fake*Repository) make the full stack deterministic — no SAF
 * picker, no network, no decoder.
 *
 * What this covers that the DB-gated suites can't:
 *  - `FakePlayer` queue commands — set/add/move/remove + indexed seek used to
 *    throw `IllegalStateException` ("Missing implementation to handle
 *    COMMAND_SET_MEDIA_ITEM" / COMMAND_CHANGE_MEDIA_ITEMS). Regression-pinned
 *    at controller level ([fakePlayerQueueCommandsWork]) and end-to-end via
 *    the queue sheet ([queueSheetListsItemsAndRemovesRow]).
 *  - Playlists tab (create → appears), Favorites tab (heart → appears) and the
 *    5-button transport — the current-ui paths other suites don't touch.
 *
 * Flow shape follows the YouTube Music "Up Next" sheet and Spotify's
 * playlist-create dialog (see QueueSheet's own design note).
 *
 * The live fakes are read through `StubRuntime` (registered in each fake's
 * `init`) reflectively — direct references to `com.pianostream.app.fake.*`
 * would break real-flavor test compilation since that package only exists
 * under `src/stub`. The fakes are process singletons, so state leaks across
 * tests within one instrumentation run — every UI test resets the
 * queue/store it asserts on first. Everything skips on non-stub flavors.
 */
@RunWith(AndroidJUnit4::class)
class StubUiE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun stubField(name: String): Any? = runCatching {
        Class.forName("com.pianostream.app.fake.StubRuntime").getField(name).get(null)
    }.getOrNull()

    private fun controller(): PlaybackController =
        stubField("playbackController") as? PlaybackController
            ?: error("StubRuntime.playbackController unset — stub fakes not running")

    private fun repository(): PlaybackRepository =
        stubField("playbackRepository") as? PlaybackRepository
            ?: error("StubRuntime.playbackRepository unset — stub fakes not running")

    private fun assumeStub() {
        Assume.assumeTrue(
            "stub-only E2E: fakes are bound only under the stub source flavor",
            BuildConfig.FLAVOR_source == "stub" &&
                stubField("playbackController") != null &&
                stubField("playbackRepository") != null,
        )
    }

    private fun cdCount(cd: String, substring: Boolean = false): Int =
        compose.onAllNodesWithContentDescription(cd, substring = substring)
            .fetchSemanticsNodes().size

    private fun textCount(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun item(mediaId: String, title: String = mediaId): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

    /**
     * Deterministic 3-track queue regardless of what earlier tests left in
     * the process-wide FakePlayer.
     */
    private fun resetStubQueue() {
        onMain {
            controller().player.setMediaItems(
                listOf(
                    item("fake-0", "Fake track 0"),
                    item("fake-1", "Fake track 1"),
                    item("fake-2", "Fake track 2"),
                ),
                0,
                0L,
            )
        }
    }

    private fun openExpandedPlayer() = runBlocking {
        awaitUntil(15_000, "player dock ('Open player: …')") {
            cdCount("Open player", substring = true) >= 1
        }
        compose.onNodeWithContentDescription("Open player", substring = true)
            .performClick()
        awaitUntil(10_000, "expanded player chrome") {
            cdCount("Collapse player") == 1
        }
    }

    /**
     * Regression: COMMAND_SET_MEDIA_ITEM / COMMAND_CHANGE_MEDIA_ITEMS used to
     * hit `IllegalStateException` inside the stub FakePlayer — queueing,
     * enqueue, drag-reorder and row-remove all died. Pins set/add/move/remove,
     * the single-item set path and indexed seek at the [Player] contract.
     */
    @Test
    fun fakePlayerQueueCommandsWork() = runBlocking {
        assumeStub()
        val controller = controller()
        assertTrue(onMain { runBlocking { controller.connect() } })
        onMain {
            val player = controller.player

            player.setMediaItems(listOf(item("a"), item("b")), 0, 0L)
            assertEquals(2, player.mediaItemCount)
            assertEquals("a", player.getMediaItemAt(0).mediaId)

            player.addMediaItem(item("c"))
            assertEquals(3, player.mediaItemCount)

            player.moveMediaItem(0, 2)
            assertEquals("b", player.getMediaItemAt(0).mediaId)
            assertEquals("c", player.getMediaItemAt(2).mediaId)

            player.removeMediaItem(0)
            assertEquals(2, player.mediaItemCount)
            assertEquals("a", player.getMediaItemAt(0).mediaId)

            // Single-item path needs COMMAND_SET_MEDIA_ITEM.
            player.setMediaItem(item("solo"))
            assertEquals(1, player.mediaItemCount)
            assertEquals("solo", player.getMediaItemAt(0).mediaId)

            // Indexed seek must move the cursor (the old fake only moved the
            // position, stranding currentMediaItemIndex at 0).
            player.setMediaItems(listOf(item("x"), item("y")), 0, 0L)
            player.seekTo(1, 5_000)
            assertEquals(1, player.currentMediaItemIndex)
        }
        Unit
    }

    /**
     * Expanded player → "Open play queue" → the stub tracks render as rows
     * ("Up next" + count); a row tap seeks the cursor to it; deleting row 0
     * shrinks the sheet and shifts the cursor. YouTube Music Up-Next flow.
     */
    @Test
    fun queueSheetListsItemsAndRemovesRow() = runBlocking {
        assumeStub()
        resetStubQueue()
        openExpandedPlayer()

        compose.onNodeWithContentDescription("Open play queue").performClick()
        awaitUntil(10_000, "queue sheet lists the 3 stub tracks") {
            cdCount("Remove from queue") == 3
        }
        compose.onNodeWithText("Up next").assertIsDisplayed()
        compose.onNodeWithText("Fake track 1").assertIsDisplayed()

        // Tap-to-play row 1 → indexed seek moves the playing cursor.
        compose.onNodeWithContentDescription("Play track: Fake track 1")
            .performClick()
        awaitUntil(10_000, "queue row tap moves the cursor") {
            onMain { controller().player.currentMediaItemIndex == 1 }
        }

        // Removing the row above the cursor must shift the index too.
        compose.onAllNodesWithContentDescription("Remove from queue")
            .onFirst().performClick()
        awaitUntil(10_000, "row removal shrinks the queue") {
            cdCount("Remove from queue") == 2
        }
        awaitUntil(10_000, "cursor shifted after remove-above-current") {
            onMain { controller().player.currentMediaItemIndex == 0 }
        }
        Unit
    }

    /**
     * 5-button transport: "Play" flips to "Pause" (COMMAND_PLAY_PAUSE), and
     * "Next track" advances the cursor — the expanded player's title follows.
     */
    @Test
    fun transportPlayPauseAndNextTrack() = runBlocking {
        assumeStub()
        resetStubQueue()
        openExpandedPlayer()

        awaitUntil(10_000, "transport shows Play") { cdCount("Play") >= 1 }
        compose.onAllNodesWithContentDescription("Play").onFirst().performClick()
        awaitUntil(10_000, "transport shows Pause once playing") {
            cdCount("Pause") >= 1
        }
        compose.onAllNodesWithContentDescription("Pause").onFirst().performClick()

        compose.onAllNodesWithContentDescription("Next track").onFirst()
            .performClick()
        awaitUntil(10_000, "next-track advances the cursor") {
            onMain { controller().player.currentMediaItemIndex == 1 }
        }
        Unit
    }

    /**
     * Playlists tab → "New playlist" → name dialog → "Create" → the playlist
     * row appears in the list (Spotify-style create flow; served in-memory by
     * FakePlaybackRepository under stub).
     */
    @Test
    fun playlistsTabCreatePlaylistAppears() = runBlocking {
        assumeStub()
        runBlocking { repository().clearAll() }

        compose.onNodeWithText("Library").performClick()
        awaitUntil(10_000, "library tabs") { textCount("Playlists") >= 1 }
        compose.onNodeWithText("Playlists").performClick()

        awaitUntil(10_000, "Playlists empty state") { textCount("No playlists") >= 1 }
        compose.onAllNodesWithText("New playlist").onFirst().performClick()

        awaitUntil(10_000, "name dialog text field") {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextInput("Mixtape")
        compose.onNodeWithText("Create").performClick()

        awaitUntil(10_000, "playlist row 'Mixtape' in list") {
            textCount("Mixtape") >= 1
        }
        compose.onNodeWithText("0 tracks").assertIsDisplayed()
        Unit
    }

    /**
     * Heart the playing fake track ("Save to collection") → Library →
     * Favorites tab → the hearted row renders (FakePlaybackRepository
     * favorites flow, no SAF/network).
     */
    @Test
    fun favoritesTabShowsHeartedItem() = runBlocking {
        assumeStub()
        runBlocking { repository().clearAll() }
        val playingTitle = onMain {
            controller().player.currentMediaItem?.mediaMetadata?.title?.toString()
        } ?: error("no current item under stub")

        openExpandedPlayer()
        awaitUntil(10_000, "heart shows 'Save to collection'") {
            cdCount("Save to collection") >= 1
        }
        compose.onAllNodesWithContentDescription("Save to collection")
            .onFirst().performClick()
        awaitUntil(10_000, "heart flips to 'Remove from collection'") {
            cdCount("Remove from collection") >= 1
        }

        compose.onNodeWithContentDescription("Collapse player").performClick()
        awaitUntil(10_000, "player collapsed") { cdCount("Collapse player") == 0 }

        compose.onNodeWithText("Library").performClick()
        awaitUntil(10_000, "library tabs") { textCount("Favorites") >= 1 }
        compose.onNodeWithText("Favorites").performClick()

        awaitUntil(10_000, "hearted item '$playingTitle' in Favorites") {
            textCount(playingTitle) >= 1
        }
        Unit
    }
}
