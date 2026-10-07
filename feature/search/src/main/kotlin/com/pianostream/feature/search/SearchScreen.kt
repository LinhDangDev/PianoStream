package com.pianostream.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.TrackRow

/**
 * Search — debounced input, scope chips (All / This device / YouTube),
 * suggestions only while `sendSearchSuggestions` + YouTube scope, local
 * results + a YouTube section hidden behind the first-run disclosure (F12).
 */
@Composable
fun SearchScreen(
    /** Play a queue: rows + start position (C6 — app layer owns the player). */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            placeholder = { Text("Search discs, tracks, YouTube…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            for (scope in SearchViewModel.Scope.entries) {
                FilterChip(
                    selected = state.scope == scope,
                    onClick = { viewModel.setScope(scope) },
                    label = {
                        Text(
                            when (scope) {
                                SearchViewModel.Scope.ALL -> "All"
                                SearchViewModel.Scope.LOCAL -> "This device"
                                SearchViewModel.Scope.YOUTUBE -> "YouTube"
                            },
                        )
                    },
                )
            }
        }

        if (state.suggestions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                for (s in state.suggestions.take(3)) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.onQueryChanged(s) },
                        label = { Text(s, maxLines = 1) },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (state.localHits.isEmpty() && state.youtubeItems.isEmpty() &&
                state.query.isNotBlank() && !state.searching
            ) {
                item {
                    EmptyState(
                        title = "No results",
                        body = "Nothing in your library matches “${state.query}”.",
                    )
                }
            }
            if (state.localHits.isNotEmpty()) {
                item { SectionHeader("This device") }
                items(state.localHits, key = { it.trackId }) { hit ->
                    TrackRow(
                        title = hit.title,
                        subtitle = hit.snippet,
                        artworkUri = null,
                        durationMs = null,
                        onClick = {
                            scope.launch {
                                viewModel.mediaIdFor(hit)?.let { mediaId ->
                                    onPlayQueue(listOf(hit.toQueueItem(mediaId)), 0)
                                }
                            }
                        },
                    )
                }
            }
            if (state.youtubeEnabled) {
                item { SectionHeader("YouTube") }
                if (!state.youtubeOptedIn) {
                    item {
                        EmptyState(
                            title = "YouTube search is off",
                            body = "Enable to stream results from YouTube Music. " +
                                "Your query, Accept-Language and IP are sent to Google.",
                        )
                    }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp)) {
                            TextButton(onClick = { viewModel.acceptYoutubeDisclosure() }) {
                                Text("Enable YouTube search")
                            }
                        }
                    }
                } else {
                    items(state.youtubeItems, key = { it.videoId ?: it.title }) { item ->
                        TrackRow(
                            title = item.title,
                            subtitle = item.subtitle,
                            artworkUri = item.artworkUri,
                            durationMs = item.durationMs,
                            onClick = {
                                item.videoId?.let { vid ->
                                    onPlayQueue(listOf(item.toQueueItem(vid)), 0)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun SearchHit.toQueueItem(mediaId: String): QueueItem = QueueItem(
    position = 0,
    mediaId = mediaId,
    title = title,
    artist = snippet,
    artworkUri = null,
    source = MediaSource.LOCAL,
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
