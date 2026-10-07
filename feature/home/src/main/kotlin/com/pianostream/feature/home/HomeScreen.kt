package com.pianostream.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.PlayHistoryItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.designsystem.component.DiscCard
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.PlaybackErrorState
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.TrackRow

/**
 * Home — recently played, disc grid, composer chips, "From YouTube" row.
 */
@Composable
fun HomeScreen(
    onOpenDisc: (Long) -> Unit,
    /** Play a queue: rows + start position (C6 — app layer owns the player). */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (state.recentlyPlayed.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            items(state.recentlyPlayed, key = { it.id }) { item ->
                TrackRow(
                    title = item.title,
                    subtitle = item.artist,
                    artworkUri = item.artworkUri,
                    durationMs = null,
                    onClick = { onPlayQueue(listOf(item.toQueueItem()), 0) },
                )
            }
        }

        if (state.discs.isEmpty()) {
            item {
                EmptyState(
                    title = "No discs yet",
                    body = "Grant your music folder in Library to scan your collection.",
                )
            }
        } else {
            item { SectionHeader("Your discs") }
            item {
                // Nested grid inside LazyColumn needs a bounded height —
                // discs render two per row here (full grid lives in Library).
                val rows = (state.discs.size + 1) / 2
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height((212 * rows.coerceAtLeast(1)).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false,
                ) {
                    items(state.discs, key = { it.id }) { disc ->
                        DiscCard(
                            title = disc.title,
                            subtitle = disc.performer ?: disc.composer,
                            artworkUri = disc.artworkUri,
                            onClick = { onOpenDisc(disc.id) },
                        )
                    }
                }
            }
        }

        if (state.composers.isNotEmpty()) {
            item { SectionHeader("Composers") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.composers) { c ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = { Text(c) },
                        )
                    }
                }
            }
        }

        if (state.youtubeEnabled) {
            item { SectionHeader("From YouTube") }
            state.youtubeError?.let { err ->
                item { PlaybackErrorState(message = err, onRetry = null) }
            }
            for (section in state.youtubeSections.take(3)) {
                item {
                    Text(
                        section.title.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(section.items) { item ->
                            DiscCard(
                                title = item.title,
                                subtitle = item.subtitle,
                                artworkUri = item.artworkUri,
                                onClick = {
                                    item.videoId?.let { vid ->
                                        onPlayQueue(listOf(item.toQueueItem(vid)), 0)
                                    }
                                },
                                modifier = Modifier.width(160.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun PlayHistoryItem.toQueueItem(): QueueItem = QueueItem(
    position = 0,
    mediaId = mediaId,
    title = title,
    artist = artist,
    artworkUri = artworkUri,
    source = source,
    durationMs = null,
    addedAt = System.currentTimeMillis(),
)

private fun com.pianostream.core.youtube.api.YouTubeItem.toQueueItem(
    videoId: String,
): QueueItem = QueueItem(
    position = 0,
    mediaId = PianoStreamUri.stream(videoId),
    title = title,
    artist = subtitle,
    artworkUri = artworkUri,
    source = MediaSource.YOUTUBE,
    durationMs = durationMs,
    addedAt = System.currentTimeMillis(),
)
