package com.pianostream.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.designsystem.component.DiscCard
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.TrackRow

/**
 * Library — tabs Discs / Tracks / Composers / Playlists / Favorites (C5),
 * plus the SAF-root picker surface and shrink-confirmation dialog (M2).
 */
@Composable
fun LibraryScreen(
    onOpenTrack: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launchPicker = rememberLibraryRootPicker(viewModel)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Discs", "Tracks", "Composers", "Playlists", "Favorites")

    Column(modifier.fillMaxSize()) {
        if (!state.hasRoot) {
            EmptyState(
                title = "No library folder",
                body = "Pick the folder holding your discs to build the library.",
            )
            Row(Modifier.padding(16.dp)) {
                TextButton(onClick = launchPicker) { Text("Pick library folder") }
            }
            state.grantError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            state.rootPath?.let {
                Text(
                    "Library: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label) },
                    )
                }
            }
            when (tab) {
                0 -> DiscsTab(state, onSelect = { viewModel.selectDisc(it) })
                1 -> TracksTab(state, onOpenTrack)
                2 -> ComposersTab(state)
                3 -> PlaylistsTab(state, onOpenTrack)
                4 -> FavoritesTab(state, onOpenTrack)
            }
        }
    }

    val pending = state.pendingShrink
    if (pending != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissShrink,
            title = { Text("Fewer discs found") },
            text = {
                Text(
                    "The scan found ${pending.discCount} discs but your library " +
                        "previously had more. Keep the missing discs or remove them? " +
                        "Removing cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmShrink) { Text("Remove missing") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissShrink) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun DiscsTab(
    state: LibraryViewModel.UiState,
    onSelect: (com.pianostream.core.data.library.model.Disc) -> Unit,
) {
    if (state.discs.isEmpty()) {
        EmptyState("No discs", "Run a scan to populate your library.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.discs, key = { it.id }) { disc ->
            DiscCard(
                title = disc.title,
                subtitle = disc.performer ?: disc.composer,
                artworkUri = disc.artworkUri,
                onClick = { onSelect(disc) },
            )
        }
    }
}

@Composable
private fun TracksTab(
    state: LibraryViewModel.UiState,
    onOpenTrack: (String) -> Unit,
) {
    if (state.selectedDisc == null) {
        EmptyState("Pick a disc", "Track list appears here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(state.tracks, key = { it.id }) { t ->
            TrackRow(
                title = t.title,
                subtitle = t.performer ?: t.composer,
                artworkUri = null,
                durationMs = t.endMs?.let { it - t.startMs },
                onClick = { onOpenTrack("track:${t.id}") },
            )
        }
    }
}

@Composable
private fun ComposersTab(state: LibraryViewModel.UiState) {
    if (state.composers.isEmpty()) {
        EmptyState("No composers", "Composers come from track SONGWRITER tags.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(state.composers) { c ->
            Text(
                c,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun PlaylistsTab(
    state: LibraryViewModel.UiState,
    onOpenTrack: (String) -> Unit,
) {
    if (state.queue.isEmpty()) {
        EmptyState("Nothing queued", "Your current playback queue shows here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(state.queue, key = { it.id }) { q ->
            TrackRow(
                title = q.title,
                subtitle = q.artist,
                artworkUri = q.artworkUri,
                durationMs = q.durationMs,
                onClick = { onOpenTrack(q.mediaId) },
            )
        }
    }
}

@Composable
private fun FavoritesTab(
    state: LibraryViewModel.UiState,
    onOpenTrack: (String) -> Unit,
) {
    Column {
        if (state.missingFavorites > 0) {
            Text(
                "${state.missingFavorites} favourites no longer in your library",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (state.favorites.isEmpty()) {
            EmptyState("No favourites", "Heart a track to see it here.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            items(state.favorites, key = { it.mediaId }) { f ->
                TrackRow(
                    title = f.title,
                    subtitle = f.artist,
                    artworkUri = f.artworkUri,
                    durationMs = null,
                    onClick = { onOpenTrack(f.mediaId) },
                )
            }
        }
    }
}
