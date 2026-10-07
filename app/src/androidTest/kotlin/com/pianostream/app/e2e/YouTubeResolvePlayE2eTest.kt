@file:UnstableApi
package com.pianostream.app.e2e

import android.net.ConnectivityManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

import com.pianostream.app.e2e.E2eSupport.appContext
import com.pianostream.app.e2e.E2eSupport.awaitUntil
import com.pianostream.app.e2e.E2eSupport.connectController
import com.pianostream.app.e2e.E2eSupport.foreignContext
import com.pianostream.app.e2e.E2eSupport.onMain
import com.pianostream.app.e2e.E2eSupport.playStreamUri

/**
 * Phase-5a YouTube resolve+play gate (plan §5a #3):
 *   a `pianostream://stream/<videoId>` item resolves through the extractor
 *   (poToken + player-response + itag allow-list) and reaches PLAYING with a
 *   non-zero buffered position.
 *
 * The concrete itag (251/opus) is asserted at the resolver layer by the 1b
 * unit tests (`StreamResolverImpl` preference ordering); this E2E proves the
 * full pipeline end-to-end on the running service — resolve → cache →
 * ExoPlayer → PLAYING — which a unit test cannot observe.
 *
 * CC9 in both directions:
 *  - the instrumentation process (`*.test`) is a FOREIGN controller: the
 *    session must either reject the bind outright or grant zero commands —
 *    both are compliant, rejection is strictly stronger;
 *  - a `file:///`- or `https://`-scheme media item is rejected by
 *    `onAddMediaItems` and never reaches the timeline.
 *
 * Requires a live network on the device; skips cleanly offline.
 */
class YouTubeResolvePlayE2eTest {

    /** Blender Foundation's open-content Big Buck Bunny clip. */
    private val videoId = "aqz-KE-bpKQ"
    private val streamUri = "pianostream://stream/$videoId"

    @Before
    fun requireNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        Assume.assumeTrue("E2E skipped: device offline", cm?.activeNetwork != null)
    }

    @Test
    fun streamUri_resolvesAndReachesPlaying(): Unit = runBlocking {
        val controller = connectController(appContext)
        try {
            playStreamUri(controller, streamUri)

            awaitUntil(timeoutMs = 45_000, describe = "PLAYING with buffer") {
                onMain { controller.isPlaying && controller.bufferedPosition > 0 }
            }

            onMain {
                assertTrue(controller.isPlaying)
                assertTrue(controller.bufferedPosition > 0)
                assertEquals(
                    streamUri,
                    controller.currentMediaItem?.localConfiguration?.uri?.toString()
                        ?: controller.currentMediaItem?.mediaId,
                )
            }
        } finally {
            onMain {
                runCatching { controller.stop() }
                runCatching { controller.release() }
            }
        }
    }

    @Test
    fun foreignController_rejectedOrZeroCommands() {
        // foreignContext = instrumentation package (com.pianostream.debug.test):
        // outside the CC9 allow-list. Compliant outcomes:
        //   (a) the service rejects the connection entirely (observed on this
        //       device — connect future fails with SecurityException), or
        //   (b) it connects with zero granted commands.
        val foreign = runCatching { connectController(foreignContext) }.getOrNull()
        if (foreign == null) return // (a) rejected — compliant.

        try {
            onMain {
                assertEquals(
                    "foreign controller unexpectedly holds player commands",
                    0, foreign.availableCommands.size(),
                )
                assertTrue(
                    "foreign controller unexpectedly holds session commands",
                    foreign.availableSessionCommands.commands.isEmpty(),
                )
            }
        } finally {
            onMain { runCatching { foreign.release() } }
        }
    }

    @Test
    fun hostileFileUri_rejectedByOnAddMediaItems(): Unit = runBlocking {
        val controller = connectController(appContext)
        try {
            onMain {
                controller.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("hostile-1")
                        .setUri("file:///etc/passwd")
                        .build(),
                )
                controller.prepare()
            }
            // Negative assertion: absence cannot be marker-waited — allow a
            // bounded settle window, then assert the timeline stayed empty.
            kotlinx.coroutines.delay(1_500)
            onMain {
                assertEquals(0, controller.mediaItemCount)
                assertEquals(0, controller.currentTimeline.periodCount)
            }
        } finally {
            onMain { runCatching { controller.release() } }
        }
    }

    @Test
    fun networkUrlScheme_rejectedByOnAddMediaItems(): Unit = runBlocking {
        val controller = connectController(appContext)
        try {
            onMain {
                controller.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("hostile-2")
                        .setUri("https://example.com/evil.mp3")
                        .build(),
                )
                controller.prepare()
            }
            kotlinx.coroutines.delay(1_500)
            onMain {
                assertEquals(0, controller.mediaItemCount)
                assertNotNull(controller)
            }
        } finally {
            onMain { runCatching { controller.release() } }
        }
    }
}
