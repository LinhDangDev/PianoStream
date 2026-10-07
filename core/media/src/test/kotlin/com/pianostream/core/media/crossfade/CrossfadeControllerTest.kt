package com.pianostream.core.media.crossfade

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.media.local.LocalMediaItemFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * H2/H3/L1 — synchronous fade eligibility from `MediaMetadata.extras`:
 * fade iff both fade keys are known AND differ; `local/` items without a
 * `flacUri` never fade (unfetched windows).
 */
@RunWith(RobolectricTestRunner::class)
class CrossfadeControllerTest {

    private fun item(mediaId: String, extras: Bundle? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaId)
            .apply {
                if (extras != null) {
                    setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
                }
            }
            .build()

    private fun localItem(contentKey: String, flacUri: String?): MediaItem =
        item(
            "pianostream://local/$contentKey",
            bundleOf().apply {
                if (flacUri != null) {
                    putString(LocalMediaItemFactory.EXTRA_FLAC_URI, flacUri)
                }
                putString(LocalMediaItemFactory.EXTRA_CONTENT_KEY, contentKey)
            },
        )

    private fun remoteItem(videoId: String): MediaItem =
        item(
            "pianostream://stream/$videoId",
            bundleOf(LocalMediaItemFactory.EXTRA_CONTENT_KEY to videoId),
        )

    // --- fadeKeyOf ---------------------------------------------------------

    @Test
    fun fade_key_prefers_flac_uri_over_content_key() {
        val i = localItem("d1/t1", "content://flac/d1")
        assertThat(CrossfadeController.fadeKeyOf(i)).isEqualTo("content://flac/d1")
    }

    @Test
    fun fade_key_falls_back_to_content_key() {
        assertThat(CrossfadeController.fadeKeyOf(remoteItem("abc")))
            .isEqualTo("abc")
    }

    @Test
    fun fade_key_null_when_no_extras() {
        assertThat(CrossfadeController.fadeKeyOf(item("pianostream://stream/x")))
            .isNull()
        assertThat(CrossfadeController.fadeKeyOf(null)).isNull()
    }

    // --- localWindowMissing ------------------------------------------------

    @Test
    fun local_item_without_flac_uri_is_missing_window() {
        val i = localItem("d2/t9", flacUri = null)
        assertThat(CrossfadeController.localWindowMissing(i)).isTrue()
    }

    @Test
    fun local_item_with_flac_uri_is_resolved() {
        assertThat(CrossfadeController.localWindowMissing(localItem("d2/t9", "c://f")))
            .isFalse()
    }

    @Test
    fun remote_item_is_never_missing() {
        assertThat(CrossfadeController.localWindowMissing(remoteItem("v1")))
            .isFalse()
        assertThat(CrossfadeController.localWindowMissing(item("https://x")))
            .isFalse()
    }

    // --- isEligible (L1 matrix) -------------------------------------------

    @Test
    fun remote_to_remote_with_different_keys_eligible() {
        assertThat(CrossfadeController.isEligible(remoteItem("v1"), remoteItem("v2")))
            .isTrue()
    }

    @Test
    fun remote_to_same_remote_not_eligible() {
        assertThat(CrossfadeController.isEligible(remoteItem("v1"), remoteItem("v1")))
            .isFalse()
    }

    @Test
    fun remote_to_local_different_discs_eligible() {
        assertThat(
            CrossfadeController.isEligible(
                remoteItem("v1"),
                localItem("d1/t1", "content://flac/d1"),
            ),
        ).isTrue()
    }

    @Test
    fun disc_to_disc_l1_eligible_when_flac_differs() {
        assertThat(
            CrossfadeController.isEligible(
                localItem("d1/t1", "content://flac/d1"),
                localItem("d2/t1", "content://flac/d2"),
            ),
        ).isTrue()
    }

    @Test
    fun same_disc_consecutive_tracks_never_fade() {
        assertThat(
            CrossfadeController.isEligible(
                localItem("d1/t1", "content://flac/d1"),
                localItem("d1/t2", "content://flac/d1"),
            ),
        ).isFalse()
    }

    @Test
    fun unresolved_local_window_blocks_fade_both_directions() {
        val missing = localItem("dx/t1", flacUri = null)
        assertThat(CrossfadeController.isEligible(missing, remoteItem("v1"))).isFalse()
        assertThat(CrossfadeController.isEligible(remoteItem("v1"), missing)).isFalse()
    }

    @Test
    fun null_items_never_fade() {
        assertThat(CrossfadeController.isEligible(null, remoteItem("v1"))).isFalse()
        assertThat(CrossfadeController.isEligible(remoteItem("v1"), null)).isFalse()
    }
}
