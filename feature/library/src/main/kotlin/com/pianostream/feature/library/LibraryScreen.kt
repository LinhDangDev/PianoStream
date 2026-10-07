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
import androidx.compose.material3.ButtonDefaults
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
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.designsystem.component.TrackRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora

/**
 * Library — tabs Discs / Tracks / Composers / Playlists / Favorites (C5),
 * plus the SAF-root picker surface and shrink-confirmation dialog (M2).
 */
@Composable
fun LibraryScreen(
    /** Play a queue: rows + start position (C6 — app layer owns the player). */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launchPicker = rememberLibraryRootPicker(viewModel)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Discs", "Tracks", "Composers", "Playlists", "Favorites")

    Column(modifier.fillMaxSize()) {
        // F8: grant-loss/error surface — visible regardless of hasRoot.
        state.grantError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!state.hasRoot) {
            EmptyState(
                title = "No library folder",
                body = "Pick the folder holding your discs to build the library.",
            )
            Row(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = launchPicker,
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) { Text("Pick library folder") }
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
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(
                    onClick = viewModel::rescan,
                    enabled = !state.scanning,
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) { Text(if (state.scanning) "Scanning…" else "Rescan") }
                TextButton(
                    onClick = viewModel::revokeAccess,
                    colors = ButtonDefaults.textButtonColors(contentColor = Aurora.TextSecondary),
                ) { Text("Revoke access") }
            }
            // E2 — the "N new / M removed" completion summary + scan warnings
            // (e.g. "0 discs found — refusing to wipe") must be visible; the
            // VM computed them but nothing rendered them.
            state.lastScan?.let { summary ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "Scan: ${summary.newDiscs} new, " +
                            "${summary.removedDiscs} removed, " +
                            "${summary.tracks} tracks",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    summary.warnings.forEach { w ->
                        Text(
                            w,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 8.dp,
                containerColor = Aurora.Bg,
                contentColor = Aurora.TextSecondary,
                indicator = { positions ->
                    if (tab < positions.size) {
                        androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(positions[tab]),
                            color = Aurora.Accent,
                        )
                    }
                },
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        selectedContentColor = Vinyl.Gold,
                        unselectedContentColor = Aurora.TextSecondary,
                        text = { Text(label) },
                    )
                }
            }
            when (tab) {
                0 -> DiscsTab(state, onSelect = { viewModel.selectDisc(it) })
                1 -> TracksTab(
                    state,
                    onPlayAt = { idx ->
                        viewModel.discQueue(idx)?.let { (rows, start) ->
                            onPlayQueue(rows, start)
                        }
                    },
                )
                2 -> ComposersTab(state)
                3 -> PlaylistsTab(state, onPlayQueue)
                4 -> FavoritesTab(state, onPlayQueue)
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
    onPlayAt: (Int) -> Unit,
) {
    if (state.selectedDisc == null) {
        EmptyState("Pick a disc", "Track list appears here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        itemsIndexed(state.tracks, key = { _, t -> t.id }) { idx, t ->
            TrackRow(
                title = t.title,
                subtitle = t.performer ?: t.composer,
                artworkUri = null,
                durationMs = t.endMs?.let { it - t.startMs },
                onClick = { onPlayAt(idx) },
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
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
) {
    if (state.queue.isEmpty()) {
        EmptyState("Nothing queued", "Your current playback queue shows here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        itemsIndexed(state.queue, key = { _, q -> q.id }) { idx, q ->
            TrackRow(
                title = q.title,
                subtitle = q.artist,
                artworkUri = q.artworkUri,
                durationMs = q.durationMs,
                onClick = { onPlayQueue(state.queue, idx) },
            )
        }
    }
}

@Composable
private fun FavoritesTab(
    state: LibraryViewModel.UiState,
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
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
                    onClick = { onPlayQueue(listOf(f.toQueueItem()), 0) },
                )
            }
        }
    }
}

/** Favourite row → single-entry queue. */
private fun FavoriteItem.toQueueItem(): QueueItem = QueueItem(
    position = 0,
    mediaId = mediaId,
    title = title,
    artist = artist,
    artworkUri = artworkUri,
    source = source,
    durationMs = null,
    addedAt = System.currentTimeMillis(),
)
