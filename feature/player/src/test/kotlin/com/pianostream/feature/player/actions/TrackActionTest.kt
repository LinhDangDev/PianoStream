package com.pianostream.feature.player.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sheet is pure UI, so the testable contract lives in the row model:
 * which actions render, in what order, and how [TrackAction.ToggleFavorite]
 * flips its copy with the favourite state.
 */
class TrackActionTest {

    @Test
    fun `actionRows keeps declaration order and filters to enabled`() {
        val enabled = setOf(
            TrackAction.SleepTimer,
            TrackAction.AddToPlaylist,
            TrackAction.ToggleFavorite,
        )

        assertThat(actionRows(enabled)).containsExactly(
            TrackAction.ToggleFavorite,
            TrackAction.AddToPlaylist,
            TrackAction.SleepTimer,
        ).inOrder()
    }

    @Test
    fun `actionRows renders nothing when enabled is empty`() {
        assertThat(actionRows(emptySet())).isEmpty()
    }

    @Test
    fun `toggleFavorite label flips with favourite state`() {
        assertThat(TrackAction.ToggleFavorite.label(isFavorite = false))
            .isEqualTo("Save to collection")
        assertThat(TrackAction.ToggleFavorite.label(isFavorite = true))
            .isEqualTo("Remove from collection")
    }

    @Test
    fun `every action has a non-blank label`() {
        for (action in TrackAction.entries) {
            assertThat(action.label(isFavorite = false)).isNotEmpty()
            assertThat(action.label(isFavorite = true)).isNotEmpty()
        }
    }

    @Test
    fun `toggleFavorite icon flips with favourite state`() {
        assertThat(TrackAction.ToggleFavorite.icon(isFavorite = false))
            .isNotEqualTo(TrackAction.ToggleFavorite.icon(isFavorite = true))
    }
}
