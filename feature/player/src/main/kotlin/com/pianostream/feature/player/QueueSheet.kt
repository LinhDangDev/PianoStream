package com.pianostream.feature.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.TrackRow

/**
 * Queue sheet over `player.currentTimeline` (M9): rows and actions are inert
 * while `isPlaylistReady` is false — a Room-restored queue rendered over an
 * empty timeline must accept no input.
 */
@Composable
fun QueueSheet(
    viewModel: PlayerViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = viewModel.player

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item { SectionHeader("Up next") }
        if (!state.isPlaylistReady) {
            item {
                EmptyState(
                    title = "Queue empty",
                    body = "Play something to fill the queue.",
                )
            }
        } else if (player != null) {
            val timeline = player.currentTimeline
            val count = timeline.windowCount
            items(count) { i ->
                val window = timeline.getWindow(i, androidx.media3.common.Timeline.Window())
                val item = window.mediaItem
                TrackRow(
                    title = item.mediaMetadata.title?.toString() ?: "Track ${i + 1}",
                    subtitle = item.mediaMetadata.artist?.toString(),
                    artworkUri = item.mediaMetadata.artworkUri?.toString(),
                    durationMs = null,
                    isCurrent = i == player.currentMediaItemIndex,
                    artworkShape = CircleShape,
                    onClick = { viewModel.playAt(i) },
                    trailing = {
                        IconButton(onClick = { viewModel.removeAt(i) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove from queue",
                            )
                        }
                    },
                )
            }
        }
    }
}
