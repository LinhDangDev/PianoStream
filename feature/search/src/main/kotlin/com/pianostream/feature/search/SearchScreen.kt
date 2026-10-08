package com.pianostream.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora

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
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Aurora.TextSecondary)
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Vinyl.Gold,
                unfocusedBorderColor = Aurora.Hairline,
                cursorColor = Vinyl.Gold,
                focusedTextColor = Aurora.TextPrimary,
                unfocusedContainerColor = Color(0xFF1E1F23),
                focusedContainerColor = Color(0xFF1E1F23),
            ),
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
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF1E1F23),
                        labelColor = Aurora.TextSecondary,
                        selectedContainerColor = Vinyl.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = Vinyl.Gold,
                    ),
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
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF1E1F23),
                            labelColor = Aurora.TextSecondary,
                        ),
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
                    // Resolve the row's mediaId once so the heart can show
                    // saved state and toggle the same id the queue plays.
                    val hitMediaId by produceState<String?>(initialValue = null, hit.trackId) {
                        value = viewModel.mediaIdFor(hit)
                    }
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
                        trailing = {
                            val fav = hitMediaId != null && hitMediaId in state.favoriteIds
                            Icon(
                                imageVector = if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (fav) "Remove from collection" else "Save to collection",
                                tint = if (fav) Vinyl.Gold else Vinyl.GoldDim,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        hitMediaId?.let { id ->
                                            viewModel.toggleFavorite(
                                                id, hit.title, hit.snippet, null, MediaSource.LOCAL,
                                            )
                                        }
                                    },
                            )
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
                            TextButton(
                                onClick = { viewModel.acceptYoutubeDisclosure() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                            ) {
                                Text("Enable YouTube search")
                            }
                        }
                    }
                } else {
                    items(state.youtubeItems, key = { it.videoId ?: it.title }) { item ->
                        val favMediaId = item.videoId?.let(PianoStreamUri::stream)
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
                            trailing = {
                                if (favMediaId != null) {
                                    val fav = favMediaId in state.favoriteIds
                                    Icon(
                                        imageVector = if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = if (fav) "Remove from collection" else "Save to collection",
                                        tint = if (fav) Vinyl.Gold else Vinyl.GoldDim,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clickable {
                                                viewModel.toggleFavorite(
                                                    favMediaId, item.title, item.subtitle, item.artworkUri, MediaSource.YOUTUBE,
                                                )
                                            },
                                    )
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
