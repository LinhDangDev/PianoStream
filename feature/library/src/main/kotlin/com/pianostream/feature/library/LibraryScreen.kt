package com.pianostream.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.Playlist
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.TrackRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.pianostream.core.designsystem.component.SINGLE_LABEL_RATIO
import com.pianostream.core.designsystem.component.SingleBadge
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
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
    var actionsOpen by remember { mutableStateOf(false) }
    /** Track handed to the add-to-playlist sheet; null = sheet closed. */
    var addToPlaylistItem by remember { mutableStateOf<QueueItem?>(null) }
    val tabs = listOf("Discs", "Tracks", "Composers", "Playlists", "Favorites")

    // A single tap on a one-track disc emits a play request (CC1 — the
    // queue still builds from the selected disc's tracks).
    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { (rows, start) ->
            onPlayQueue(rows, start)
        }
    }

    Column(modifier.fillMaxSize()) {
        // Screen header — app name only; library actions hide in the ⋯ menu.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Music",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Aurora.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (state.hasRoot) {
                Box {
                    IconButton(onClick = { actionsOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Library actions",
                            tint = Aurora.TextSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded = actionsOpen,
                        onDismissRequest = { actionsOpen = false },
                        containerColor = Vinyl.BgTop,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.scanning) "Scanning…" else "Rescan",
                                    color = Vinyl.Gold,
                                )
                            },
                            onClick = {
                                actionsOpen = false
                                viewModel.rescan()
                            },
                            enabled = !state.scanning,
                        )
                        DropdownMenuItem(
                            text = { Text("Revoke access", color = Aurora.TextSecondary) },
                            onClick = {
                                actionsOpen = false
                                viewModel.revokeAccess()
                            },
                        )
                    }
                }
            }
        }
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
                body = "Pick the folder holding your music to build the library.",
            )
            Row(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = launchPicker,
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) { Text("Pick library folder") }
            }
        } else {
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
                0 -> DiscsTab(
                    state,
                    onSelect = {
                        viewModel.selectDisc(it)
                        // Show the selection instead of looking like a no-op:
                        // albums land on their track list, a single fires the
                        // play request above and lands here already playing.
                        tab = 1
                    },
                )
                1 -> TracksTab(
                    state,
                    onPlayAt = { idx ->
                        viewModel.discQueue(idx)?.let { (rows, start) ->
                            onPlayQueue(rows, start)
                        }
                    },
                    onAddToPlaylist = { idx ->
                        viewModel.trackQueueItem(idx)?.let { addToPlaylistItem = it }
                    },
                )
                2 -> ComposersTab(state)
                3 -> PlaylistsTab(
                    state,
                    onSelect = viewModel::selectPlaylist,
                    onCreate = viewModel::createPlaylist,
                    onRename = viewModel::renameSelectedPlaylist,
                    onDelete = viewModel::deleteSelectedPlaylist,
                    onRemoveTrack = viewModel::removeFromSelectedPlaylist,
                    onPlayQueue = onPlayQueue,
                )
                4 -> FavoritesTab(
                    state,
                    onPlayQueue,
                    onAddToPlaylist = { addToPlaylistItem = it.toQueueItem() },
                )
            }
        }
    }

    // "Add to playlist" target sheet — opened from Tracks/Favorites rows.
    val adding = addToPlaylistItem
    if (adding != null) {
        AddToPlaylistSheet(
            playlists = state.playlists,
            onPick = { viewModel.addToPlaylist(it.id, adding); addToPlaylistItem = null },
            onNew = { viewModel.createPlaylistAndAdd(it, adding); addToPlaylistItem = null },
            onDismiss = { addToPlaylistItem = null },
        )
    }

    val pending = state.pendingShrink
    if (pending != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissShrink,
            containerColor = Vinyl.BgTop,
            title = { Text("Fewer discs found") },
            text = {
                Text(
                    "The scan found ${pending.discCount} discs but your library " +
                        "previously had more. Keep the missing discs or remove them? " +
                        "Removing cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmShrink,
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) { Text("Remove missing") }
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
    onSelect: (Disc) -> Unit,
) {
    if (state.discs.isEmpty()) {
        EmptyState("No discs", "Run a scan to import albums and loose tracks.")
        return
    }
    // Loose files land as one-track discs (Singles.kt). Like Apple Music's
    // "Singles & EPs" split they group under their own header once present;
    // an albums-only library keeps the plain list.
    val albums = state.discs.filterNot { it.isSingle() }
    val singles = state.discs.filter { it.isSingle() }
    // List rows (mini vinyl thumb + title) — denser than the card grid.
    // The wrapper keeps the "Open disc" contentDescription E2E asserts on
    // while TrackRow owns the visual row.
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (albums.isNotEmpty() && singles.isNotEmpty()) {
            item(key = "header-albums") { SectionHeader("Albums") }
        }
        items(albums, key = { it.id }) { disc ->
            Box(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Open disc: ${disc.title}"
                },
            ) {
                TrackRow(
                    title = disc.title,
                    subtitle = disc.performer ?: disc.composer,
                    artworkUri = disc.artworkUri,
                    durationMs = null,
                    onClick = { onSelect(disc) },
                )
            }
        }
        if (singles.isNotEmpty()) {
            item(key = "header-singles") { SectionHeader("Singles") }
            items(singles, key = { it.id }) { disc ->
                Box(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "Open disc: ${disc.title}"
                    },
                ) {
                    TrackRow(
                        title = disc.title,
                        subtitle = disc.performer ?: disc.composer,
                        artworkUri = disc.artworkUri,
                        durationMs = null,
                        onClick = { onSelect(disc) },
                        trailing = { SingleBadge() },
                    )
                }
            }
        }
    }
}

@Composable
private fun TracksTab(
    state: LibraryViewModel.UiState,
    onPlayAt: (Int) -> Unit,
    onAddToPlaylist: (Int) -> Unit,
) {
    val disc = state.selectedDisc
    if (disc == null) {
        EmptyState("Pick a disc", "Track list appears here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item { DiscHeader(disc) }
        itemsIndexed(state.tracks, key = { _, t -> t.id }) { idx, t ->
            TrackRow(
                title = t.title,
                subtitle = t.performer ?: t.composer,
                artworkUri = disc.artworkUri,
                durationMs = t.endMs?.let { it - t.startMs },
                onClick = { onPlayAt(idx) },
                trailing = {
                    IconButton(onClick = { onAddToPlaylist(idx) }) {
                        Icon(
                            Icons.Outlined.PlaylistAdd,
                            contentDescription = "Add to playlist",
                            tint = Aurora.TextSecondary,
                        )
                    }
                },
            )
        }
    }
}

/**
 * The selected disc's header — vinyl thumb (artwork label, or the red
 * placeholder when a single's tags carry no art) + title + credit line.
 * Singles get the 7-inch label and a "Single" type prefix like YouTube
 * Music's "Single · Artist" line.
 */
@Composable
private fun DiscHeader(disc: Disc) {
    val single = disc.isSingle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(72.dp)) {
            VinylDisc(
                artworkUri = disc.artworkUri,
                title = disc.title,
                modifier = Modifier.fillMaxSize(),
                labelRatio = if (single) SINGLE_LABEL_RATIO else Vinyl.LABEL_RATIO,
            )
            if (single) {
                SingleBadge(Modifier.align(Alignment.TopStart), diameter = 20.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                disc.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val credit = disc.performer ?: disc.composer
            Text(
                when {
                    single && credit != null -> "Single · $credit"
                    single -> "Single"
                    else -> credit.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = Aurora.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

/**
 * Playlists — the list view (each row: name + track count, "New playlist"
 * action up top) or the selected playlist's detail view (back arrow, ⋯
 * Rename/Delete menu, ordered track rows that play through [onPlayQueue]).
 */
@Composable
private fun PlaylistsTab(
    state: LibraryViewModel.UiState,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
) {
    val selectedId = state.selectedPlaylistId
    if (selectedId == null) {
        var createOpen by remember { mutableStateOf(false) }
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { createOpen = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Vinyl.Gold)
                Spacer(Modifier.width(12.dp))
                Text(
                    "New playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Vinyl.Gold,
                )
            }
            if (state.playlists.isEmpty()) {
                EmptyState(
                    "No playlists",
                    "Create one, then add tracks from Tracks or Favourites.",
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.playlists, key = { it.id }) { p ->
                        TrackRow(
                            title = p.name,
                            subtitle = "${p.trackCount} tracks",
                            artworkUri = null,
                            durationMs = null,
                            onClick = { onSelect(p.id) },
                        )
                    }
                }
            }
        }
        if (createOpen) {
            PlaylistNameDialog(
                title = "New playlist",
                confirmLabel = "Create",
                onConfirm = { onCreate(it); createOpen = false },
                onDismiss = { createOpen = false },
            )
        }
        return
    }

    // Detail view.
    val playlist = state.playlists.firstOrNull { it.id == selectedId }
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = { onSelect(null) }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to playlists",
                    tint = Aurora.TextSecondary,
                )
            }
            Text(
                playlist?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Playlist actions",
                        tint = Aurora.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Vinyl.BgTop,
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", color = Vinyl.Gold) },
                        onClick = { menuOpen = false; renameOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist", color = Aurora.TextSecondary) },
                        onClick = { menuOpen = false; deleteOpen = true },
                    )
                }
            }
        }
        if (state.playlistTracks.isEmpty()) {
            EmptyState(
                "Empty playlist",
                "Add tracks from the Tracks or Favourites tabs.",
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                itemsIndexed(state.playlistTracks, key = { _, t -> t.mediaId }) { idx, t ->
                    TrackRow(
                        title = t.title,
                        subtitle = t.artist,
                        artworkUri = t.artworkUri,
                        durationMs = t.durationMs,
                        onClick = { onPlayQueue(state.playlistTracks, idx) },
                        trailing = {
                            IconButton(onClick = { onRemoveTrack(t.mediaId) }) {
                                Icon(
                                    Icons.Outlined.RemoveCircleOutline,
                                    contentDescription = "Remove from playlist",
                                    tint = Aurora.TextSecondary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
    if (renameOpen) {
        PlaylistNameDialog(
            title = "Rename playlist",
            initial = playlist?.name.orEmpty(),
            confirmLabel = "Save",
            onConfirm = { onRename(it); renameOpen = false },
            onDismiss = { renameOpen = false },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = Vinyl.BgTop,
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "\"${playlist?.name.orEmpty()}\" and its track list are removed. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteOpen = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Aurora.Error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Name-entry dialog shared by "New playlist" and "Rename" — charcoal card,
 * gold confirm, same convention as the shrink-confirmation dialog.
 */
@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
        title = { Text(title, color = Aurora.TextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name", color = Aurora.TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Aurora.TextPrimary,
                    unfocusedTextColor = Aurora.TextPrimary,
                    cursorColor = Vinyl.Gold,
                    focusedBorderColor = Vinyl.Gold,
                    unfocusedBorderColor = Aurora.Hairline,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * "Add to playlist" sheet — playlists as rows plus a "New playlist" row
 * that creates-then-adds (mirrors the queue sheet's ModalBottomSheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { newOpen = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Vinyl.Gold)
                Spacer(Modifier.width(12.dp))
                Text(
                    "New playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Vinyl.Gold,
                )
            }
            playlists.forEach { p ->
                TrackRow(
                    title = p.name,
                    subtitle = "${p.trackCount} tracks",
                    artworkUri = null,
                    durationMs = null,
                    onClick = { onPick(p) },
                )
            }
            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet — create one above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
    if (newOpen) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onConfirm = { onNew(it); newOpen = false },
            onDismiss = { newOpen = false },
        )
    }
}

@Composable
private fun FavoritesTab(
    state: LibraryViewModel.UiState,
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    onAddToPlaylist: (FavoriteItem) -> Unit,
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
                    trailing = {
                        IconButton(onClick = { onAddToPlaylist(f) }) {
                            Icon(
                                Icons.Outlined.PlaylistAdd,
                                contentDescription = "Add to playlist",
                                tint = Aurora.TextSecondary,
                            )
                        }
                    },
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
