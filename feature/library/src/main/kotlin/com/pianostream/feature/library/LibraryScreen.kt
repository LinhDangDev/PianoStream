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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.data.playback.model.FavoriteItem
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.Playlist
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.TrackRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.settings.LibraryPrefsStore
import com.pianostream.core.designsystem.component.DiscCard
import com.pianostream.core.designsystem.component.SINGLE_LABEL_RATIO
import com.pianostream.core.designsystem.component.SingleBadge
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora
import java.util.Locale

/**
 * Library — tabs Discs / Tracks / Composers / Playlists / Favorites (C5),
 * plus the SAF-root picker surface and shrink-confirmation dialog (M2).
 */
@Composable
fun LibraryScreen(
    /** Play a queue: rows + start position (C6 — app layer owns the player). */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Composers-tab row tap → artist page (wired by the app nav layer). */
    onOpenArtist: (String) -> Unit = {},
    /** Track-row ⋯ in the Tracks tab, shown only when provided (app nav wires
     *  it to the TrackActionsSheet). */
    onTrackActions: ((Track) -> Unit)? = null,
    /** Disc long-press ⋯ in the Discs tab → DiscActionsSheet (app nav maps
     *  the action to queue/artist work). */
    onDiscAction: ((Disc, DiscAction) -> Unit)? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launchPicker = rememberLibraryRootPicker(viewModel)
    var tab by remember { mutableIntStateOf(0) }
    var actionsOpen by remember { mutableStateOf(false) }
    /** Track handed to the add-to-playlist sheet; null = sheet closed. */
    var addToPlaylistItem by remember { mutableStateOf<QueueItem?>(null) }
    val tabs = listOf("Discs", "Tracks", "Composers", "Playlists", "Favorites", "Downloads")

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
            // "Find in library" — pinned above the tabs, live-filters the
            // visible tab's rows (empty query = unfiltered).
            OutlinedTextField(
                value = state.libraryQuery,
                onValueChange = viewModel::setLibraryQuery,
                singleLine = true,
                placeholder = { Text("Search library", color = Aurora.TextSecondary) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Aurora.TextSecondary,
                    )
                },
                trailingIcon = {
                    if (state.libraryQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setLibraryQuery("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = Aurora.TextSecondary,
                            )
                        }
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Aurora.TextPrimary,
                    unfocusedTextColor = Aurora.TextPrimary,
                    cursorColor = Vinyl.Gold,
                    focusedBorderColor = Vinyl.Gold,
                    unfocusedBorderColor = Aurora.Hairline,
                    focusedContainerColor = Aurora.Surface,
                    unfocusedContainerColor = Aurora.Surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
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
                    onSetSort = viewModel::setDiscsSort,
                    onToggleView = viewModel::toggleDiscsView,
                    onDiscAction = onDiscAction,
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
                    onTrackActions = onTrackActions,
                )
                2 -> ComposersTab(state, onOpenArtist)
                3 -> PlaylistsTab(
                    state,
                    onSelect = viewModel::selectPlaylist,
                    onCreate = viewModel::createPlaylist,
                    onRename = viewModel::renameSelectedPlaylist,
                    onEditDetails = viewModel::updateSelectedPlaylistDetails,
                    onDelete = viewModel::deleteSelectedPlaylist,
                    onRemoveTrack = viewModel::removeFromSelectedPlaylist,
                    onMoveItem = viewModel::moveSelectedPlaylistItem,
                    onMerge = viewModel::mergePlaylists,
                    onDeleteMergeSource = viewModel::deleteMergeSource,
                    onDismissMergeNotice = viewModel::dismissMergeNotice,
                    onPlayQueue = onPlayQueue,
                )
                4 -> FavoritesTab(
                    state,
                    onPlayQueue,
                    onAddToPlaylist = { addToPlaylistItem = it.toQueueItem() },
                )
                5 -> DownloadsTab(
                    state,
                    onPlayQueue,
                    onRemove = viewModel::removeDownload,
                    onRetry = viewModel::retryDownload,
                )
            }
        }
    }

    // "Add to playlist" target sheet — opened from Tracks/Favorites rows.
    // Stays open across add/remove so the "Saved in"/"Save in" sections
    // move the row under the user's finger (Spotify convention).
    val adding = addToPlaylistItem
    if (adding != null) {
        val membershipFlow = remember(adding.mediaId) {
            viewModel.playlistsContaining(adding.mediaId)
        }
        val containing by membershipFlow.collectAsStateWithLifecycle(
            initialValue = emptyList(),
        )
        AddToPlaylistSheet(
            playlists = state.playlists,
            containing = containing,
            onPick = { viewModel.addToPlaylist(it.id, adding) },
            onRemove = { viewModel.removeFromPlaylist(it.id, adding.mediaId) },
            onClearAll = { viewModel.removeFromAllPlaylists(adding.mediaId) },
            onNew = { viewModel.createPlaylistAndAdd(it, adding) },
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

/**
 * Discs — right-aligned controls row (⋯ sort sheet + list↔grid toggle,
 * both persisted in `pianostream.library`), then the rows/cards.
 * Sort fields: RECENT → `scannedAt` desc (newest scan first), TITLE_AZ →
 * `title`, ARTIST → `performer ?: composer`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscsTab(
    state: LibraryViewModel.UiState,
    onSelect: (Disc) -> Unit,
    onSetSort: (LibraryPrefsStore.DiscsSort) -> Unit,
    onToggleView: () -> Unit,
    onDiscAction: ((Disc, DiscAction) -> Unit)? = null,
) {
    val query = state.libraryQuery
    var sortOpen by remember { mutableStateOf(false) }
    var discTarget by remember { mutableStateOf<Disc?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = { sortOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Sort discs",
                    tint = Aurora.TextSecondary,
                )
            }
            IconButton(onClick = onToggleView) {
                Icon(
                    if (state.discsView == LibraryPrefsStore.DiscsView.LIST) {
                        Icons.Filled.GridView
                    } else {
                        Icons.AutoMirrored.Filled.ViewList
                    },
                    contentDescription = if (
                        state.discsView == LibraryPrefsStore.DiscsView.LIST
                    ) {
                        "Show as grid"
                    } else {
                        "Show as list"
                    },
                    tint = Aurora.TextSecondary,
                )
            }
        }
        if (state.discs.isEmpty()) {
            EmptyState("No discs", "Run a scan to import albums and loose tracks.")
        } else {
            val visible = remember(state.discs, state.discsSort, query) {
                val filtered =
                    if (query.isBlank()) {
                        state.discs
                    } else {
                        state.discs.filter { it.matchesLibraryQuery(query) }
                    }
                when (state.discsSort) {
                    LibraryPrefsStore.DiscsSort.RECENT -> filtered.sortedWith(
                        compareByDescending<Disc> { it.scannedAt }
                            .thenByDescending { it.id },
                    )
                    LibraryPrefsStore.DiscsSort.TITLE_AZ ->
                        filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
                    LibraryPrefsStore.DiscsSort.ARTIST -> filtered.sortedBy {
                        (it.performer ?: it.composer).orEmpty().lowercase(Locale.ROOT)
                    }
                }
            }
            if (visible.isEmpty()) {
                EmptyState(
                    "Nothing found in library",
                    "No matches for \"$query\".",
                )
            } else {
                // Loose files land as one-track discs (Singles.kt). Like
                // Apple Music's "Singles & EPs" split they group under their
                // own header once present; an albums-only library keeps the
                // plain list.
                val albums = visible.filterNot { it.isSingle() }
                val singles = visible.filter { it.isSingle() }
                if (state.discsView == LibraryPrefsStore.DiscsView.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            bottom = 96.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (albums.isNotEmpty() && singles.isNotEmpty()) {
                            item(
                                key = "header-albums",
                                span = { GridItemSpan(maxLineSpan) },
                            ) { SectionHeader("Albums") }
                        }
                        items(albums, key = { it.id }) { disc ->
                            DiscCard(
                                title = disc.title,
                                subtitle = disc.performer ?: disc.composer,
                                artworkUri = disc.artworkUri,
                                onClick = { onSelect(disc) },
                                onLongClick = if (onDiscAction != null) {
                                    { discTarget = disc }
                                } else {
                                    null
                                },
                                isSingle = disc.isSingle(),
                            )
                        }
                        if (singles.isNotEmpty()) {
                            item(
                                key = "header-singles",
                                span = { GridItemSpan(maxLineSpan) },
                            ) { SectionHeader("Singles") }
                            items(singles, key = { it.id }) { disc ->
                                DiscCard(
                                    title = disc.title,
                                    subtitle = disc.performer ?: disc.composer,
                                    artworkUri = disc.artworkUri,
                                    onLongClick = if (onDiscAction != null) {
                                        { discTarget = disc }
                                    } else {
                                        null
                                    },
                                    onClick = { onSelect(disc) },
                                    isSingle = true,
                                )
                            }
                        }
                    }
                } else {
                    // List rows (mini vinyl thumb + title) — denser than the
                    // card grid. The wrapper keeps the "Open disc"
                    // contentDescription E2E asserts on while TrackRow owns
                    // the visual row.
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
                                    onLongClick = if (onDiscAction != null) {
                                        { discTarget = disc }
                                    } else {
                                        null
                                    },
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
                                        onLongClick =
                                            if (onDiscAction != null) {
                                                { discTarget = disc }
                                            } else {
                                                null
                                            },
                                        trailing = { SingleBadge() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (sortOpen) {
        ModalBottomSheet(
            onDismissRequest = { sortOpen = false },
            containerColor = Vinyl.BgTop,
        ) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(
                    "Sort discs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Aurora.TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                listOf(
                    LibraryPrefsStore.DiscsSort.RECENT to "Recently added",
                    LibraryPrefsStore.DiscsSort.TITLE_AZ to "A-Z",
                    LibraryPrefsStore.DiscsSort.ARTIST to "Artist",
                ).forEach { (sort, label) ->
                    val selected = state.discsSort == sort
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetSort(sort); sortOpen = false }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) Vinyl.Gold else Aurora.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Vinyl.Gold,
                            )
                        }
                    }
                }
            }
        }
    }
    discTarget?.let { disc ->
        DiscActionsSheet(
            title = disc.title,
            subtitle = (disc.performer ?: disc.composer).orEmpty(),
            enabled = setOf(
                DiscAction.Play,
                DiscAction.Shuffle,
                DiscAction.AddToQueue,
                DiscAction.GoToArtist,
            ),
            onAction = { action ->
                discTarget = null
                onDiscAction?.invoke(disc, action)
            },
            onDismiss = { discTarget = null },
        )
    }
}

@Composable
private fun TracksTab(
    state: LibraryViewModel.UiState,
    onPlayAt: (Int) -> Unit,
    onAddToPlaylist: (Int) -> Unit,
    onTrackActions: ((Track) -> Unit)?,
) {
    val disc = state.selectedDisc
    if (disc == null) {
        EmptyState("Pick a disc", "Track list appears here.")
        return
    }
    val query = state.libraryQuery
    // Indexed pairs so play/add still address the full list while filtered.
    val visible = remember(state.tracks, query) {
        state.tracks.withIndex().filter { it.value.matchesLibraryQuery(query) }
    }
    if (query.isNotBlank() && visible.isEmpty() && state.tracks.isNotEmpty()) {
        Column {
            DiscHeader(disc)
            EmptyState("Nothing found in library", "No matches for \"$query\".")
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item { DiscHeader(disc) }
        itemsIndexed(visible, key = { _, t -> t.value.id }) { _, iv ->
            val t = iv.value
            TrackRow(
                title = t.title,
                subtitle = t.performer ?: t.composer,
                artworkUri = disc.artworkUri,
                durationMs = t.endMs?.let { it - t.startMs },
                onClick = { onPlayAt(iv.index) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onAddToPlaylist(iv.index) }) {
                            Icon(
                                Icons.Outlined.PlaylistAdd,
                                contentDescription = "Add to playlist",
                                tint = Aurora.TextSecondary,
                            )
                        }
                        if (onTrackActions != null) {
                            IconButton(onClick = { onTrackActions(t) }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Track actions",
                                    tint = Aurora.TextSecondary,
                                )
                            }
                        }
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
private fun ComposersTab(
    state: LibraryViewModel.UiState,
    onOpenArtist: (String) -> Unit,
) {
    if (state.composers.isEmpty()) {
        EmptyState("No composers", "Composers come from track SONGWRITER tags.")
        return
    }
    val query = state.libraryQuery
    val visible = remember(state.composers, query) {
        state.composers.filter { it.contains(query, ignoreCase = true) }
    }
    if (query.isNotBlank() && visible.isEmpty()) {
        EmptyState("Nothing found in library", "No matches for \"$query\".")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(visible) { c ->
            Text(
                c,
                style = MaterialTheme.typography.bodyLarge,
                color = Aurora.TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenArtist(c) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Playlists — the list view (each row: name + track count + generated cover
 * thumb, "New playlist" action up top) or the selected playlist's detail
 * surface ([PlaylistDetailContent]: mosaic hero, Play/Shuffle/⋯, indexed
 * rows with remove + drag-to-reorder).
 */
@Composable
private fun PlaylistsTab(
    state: LibraryViewModel.UiState,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String) -> Unit,
    onEditDetails: (String, String) -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    /** "Merge into…" — (sourceId, targetId). */
    onMerge: (Long, Long) -> Unit,
    onDeleteMergeSource: () -> Unit,
    onDismissMergeNotice: () -> Unit,
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
) {
    val selectedId = state.selectedPlaylistId
    if (selectedId == null) {
        var createOpen by remember { mutableStateOf(false) }
        var importOpen by remember { mutableStateOf(false) }
        /** Playlist whose tracks the merge picker offers targets for. */
        var mergeSource by remember { mutableStateOf<Playlist?>(null) }
        val query = state.libraryQuery
        val visible = remember(state.playlists, query) {
            state.playlists.filter { it.matchesLibraryQuery(query) }
        }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { importOpen = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = Vinyl.Gold,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Import from Spotify",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Vinyl.Gold,
                )
            }
            state.mergeNotice?.let { notice ->
                MergeNoticeRow(
                    notice = notice,
                    onDeleteSource = onDeleteMergeSource,
                    onDismiss = onDismissMergeNotice,
                )
            }
            if (state.playlists.isEmpty()) {
                EmptyState(
                    "No playlists",
                    "Create one, then add tracks from Tracks or Favourites.",
                )
            } else if (query.isNotBlank() && visible.isEmpty()) {
                EmptyState("Nothing found in library", "No matches for \"$query\".")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(visible, key = { it.id }) { p ->
                        var rowMenuOpen by remember { mutableStateOf(false) }
                        TrackRow(
                            title = p.name,
                            subtitle = "${p.trackCount} tracks",
                            artworkUri = p.coverUris.firstOrNull(),
                            durationMs = null,
                            onClick = { onSelect(p.id) },
                            trailing = {
                                Box {
                                    IconButton(onClick = { rowMenuOpen = true }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription =
                                                "Actions for ${p.name}",
                                            tint = Aurora.TextSecondary,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = rowMenuOpen,
                                        onDismissRequest = { rowMenuOpen = false },
                                        containerColor = Vinyl.BgTop,
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Merge into…",
                                                    color = Vinyl.Gold,
                                                )
                                            },
                                            onClick = {
                                                rowMenuOpen = false
                                                mergeSource = p
                                            },
                                            enabled = state.playlists.size > 1,
                                        )
                                    }
                                }
                            },
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
        if (importOpen) {
            SpotifyImportSheet(
                onOpenPlaylist = { onSelect(it) },
                onDismiss = { importOpen = false },
            )
        }
        mergeSource?.let { source ->
            MergePlaylistSheet(
                source = source,
                targets = state.playlists.filter { it.id != source.id },
                onPick = { target ->
                    onMerge(source.id, target.id)
                    mergeSource = null
                },
                onDismiss = { mergeSource = null },
            )
        }
        return
    }

    // Detail view (v2 — mosaic hero + reorderable rows).
    PlaylistDetailContent(
        playlist = state.playlists.firstOrNull { it.id == selectedId },
        tracks = state.playlistTracks,
        query = state.libraryQuery,
        onBack = { onSelect(null) },
        onPlayQueue = onPlayQueue,
        onRename = onRename,
        onEditDetails = onEditDetails,
        onDelete = onDelete,
        onRemoveTrack = onRemoveTrack,
        onMoveItem = onMoveItem,
    )
}

/**
 * Name-entry dialog shared by "New playlist" and "Rename" — charcoal card,
 * gold confirm, same convention as the shrink-confirmation dialog.
 */
@Composable
internal fun PlaylistNameDialog(
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
 * Merge result line under the playlist list — "Merged 'A' into 'B' —
 * added N · skipped M duplicates", plus the "Also delete 'A'?" follow-up
 * (source is never deleted by the merge itself).
 */
@Composable
private fun MergeNoticeRow(
    notice: LibraryViewModel.MergeNotice,
    onDeleteSource: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            "Merged '${notice.sourceName}' into '${notice.targetName}' — " +
                "added ${notice.added} · skipped ${notice.skipped} " +
                ("duplicate" + if (notice.skipped == 1) "" else "s"),
            style = MaterialTheme.typography.bodySmall,
            color = Aurora.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.textButtonColors(contentColor = Aurora.Error),
        ) { Text("Delete '${notice.sourceName}'") }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss merge result",
                tint = Aurora.TextSecondary,
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Vinyl.BgTop,
            title = { Text("Delete '${notice.sourceName}'?") },
            text = {
                Text(
                    "Its tracks were merged — the playlist itself is removed. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDeleteSource() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Aurora.Error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

/**
 * "Merge into…" target picker — same conventions as [AddToPlaylistSheet]:
 * a "Find playlist" filter over TrackRows of every OTHER playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergePlaylistSheet(
    source: Playlist,
    targets: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) {
    var findQuery by remember { mutableStateOf("") }
    val visible = targets.filter { it.name.contains(findQuery, ignoreCase = true) }
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
                "Merge '${source.name}' into…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = findQuery,
                onValueChange = { findQuery = it },
                singleLine = true,
                placeholder = { Text("Find playlist", color = Aurora.TextSecondary) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Aurora.TextSecondary,
                    )
                },
                trailingIcon = {
                    if (findQuery.isNotEmpty()) {
                        IconButton(onClick = { findQuery = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = Aurora.TextSecondary,
                            )
                        }
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Aurora.TextPrimary,
                    unfocusedTextColor = Aurora.TextPrimary,
                    cursorColor = Vinyl.Gold,
                    focusedBorderColor = Vinyl.Gold,
                    unfocusedBorderColor = Aurora.Hairline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            visible.forEach { p ->
                TrackRow(
                    title = p.name,
                    subtitle = "${p.trackCount} tracks",
                    artworkUri = p.coverUris.firstOrNull(),
                    durationMs = null,
                    onClick = { onPick(p) },
                )
            }
            if (targets.isEmpty()) {
                Text(
                    "No other playlists to merge into.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else if (findQuery.isNotBlank() && visible.isEmpty()) {
                Text(
                    "No playlists match \"$findQuery\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * "Add to playlist" sheet v2 (Spotify's add sheet): a "Find playlist"
 * search field filters both sections; "Saved in" lists memberships first
 * (checkmarked, tap removes) above "Save in" (tap adds); "Clear all"
 * drops the track from every playlist. The sheet stays open so the row
 * visibly moves between sections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AddToPlaylistSheet(
    playlists: List<Playlist>,
    /** Ids of playlists already containing the track. */
    containing: List<Long>,
    onPick: (Playlist) -> Unit,
    onRemove: (Playlist) -> Unit,
    onClearAll: () -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    val saved = playlists.filter { it.id in containing }
    val others = playlists.filter { it.id !in containing }
    val savedVisible = saved.filter { it.name.contains(findQuery, true) }
    val othersVisible = others.filter { it.name.contains(findQuery, true) }
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
            OutlinedTextField(
                value = findQuery,
                onValueChange = { findQuery = it },
                singleLine = true,
                placeholder = { Text("Find playlist", color = Aurora.TextSecondary) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Aurora.TextSecondary,
                    )
                },
                trailingIcon = {
                    if (findQuery.isNotEmpty()) {
                        IconButton(onClick = { findQuery = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = Aurora.TextSecondary,
                            )
                        }
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Aurora.TextPrimary,
                    unfocusedTextColor = Aurora.TextPrimary,
                    cursorColor = Vinyl.Gold,
                    focusedBorderColor = Vinyl.Gold,
                    unfocusedBorderColor = Aurora.Hairline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { newOpen = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
            if (saved.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        "Saved in",
                        style = MaterialTheme.typography.labelLarge,
                        color = Aurora.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Aurora.Error,
                        ),
                    ) { Text("Clear all") }
                }
                savedVisible.forEach { p ->
                    TrackRow(
                        title = p.name,
                        subtitle = "${p.trackCount} tracks",
                        artworkUri = p.coverUris.firstOrNull(),
                        durationMs = null,
                        onClick = { onRemove(p) },
                        trailing = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Saved — tap to remove",
                                tint = Vinyl.Gold,
                            )
                        },
                    )
                }
            }
            if (others.isNotEmpty()) {
                Text(
                    "Save in",
                    style = MaterialTheme.typography.labelLarge,
                    color = Aurora.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                othersVisible.forEach { p ->
                    TrackRow(
                        title = p.name,
                        subtitle = "${p.trackCount} tracks",
                        artworkUri = p.coverUris.firstOrNull(),
                        durationMs = null,
                        onClick = { onPick(p) },
                    )
                }
            }
            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet — create one above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else if (findQuery.isNotBlank() &&
                savedVisible.isEmpty() && othersVisible.isEmpty()
            ) {
                Text(
                    "No playlists match \"$findQuery\".",
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
        val query = state.libraryQuery
        val visible = remember(state.favorites, query) {
            state.favorites.filter { it.matchesLibraryQuery(query) }
        }
        if (query.isNotBlank() && visible.isEmpty()) {
            EmptyState("Nothing found in library", "No matches for \"$query\".")
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            items(visible, key = { it.mediaId }) { f ->
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

/**
 * Downloads — offline YouTube audio rows: artwork, title, artist + size.
 * Tap plays through the normal queue path (the data-source stack serves
 * `filesDir/downloads/<videoId>.bin` with zero network); ⋯ → Delete.
 */
@Composable
private fun DownloadsTab(
    state: LibraryViewModel.UiState,
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (state.downloads.isEmpty()) {
        EmptyState(
            title = "No downloads",
            body = "Download YouTube tracks from search to play them offline.",
        )
        return
    }
    val query = state.libraryQuery
    val visible = remember(state.downloads, query) {
        state.downloads.filter { it.matchesLibraryQuery(query) }
    }
    if (query.isNotBlank() && visible.isEmpty()) {
        EmptyState("Nothing found in library", "No matches for \"$query\".")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(visible, key = { it.videoId }) { d ->
            var menuOpen by remember { mutableStateOf(false) }
            TrackRow(
                title = d.title,
                subtitle = downloadSubtitle(d),
                artworkUri = d.artworkUri,
                durationMs = null,
                onClick = {
                    when (d.state) {
                        DownloadState.DONE ->
                            onPlayQueue(listOf(d.toQueueItem()), 0)
                        DownloadState.FAILED -> onRetry(d.videoId)
                        else -> Unit
                    }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (d.state) {
                            DownloadState.QUEUED -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Vinyl.Gold,
                            )
                            DownloadState.DOWNLOADING -> {
                                val fraction = d.progressFraction
                                if (fraction != null) {
                                    CircularProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Vinyl.Gold,
                                        trackColor = Aurora.Hairline,
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Vinyl.Gold,
                                    )
                                }
                            }
                            else -> {}
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Download options",
                                    tint = Aurora.TextSecondary,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete download") },
                                    onClick = {
                                        menuOpen = false
                                        onRemove(d.videoId)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

private fun downloadSubtitle(d: DownloadItem): String {
    val artist = d.artist?.takeIf { it.isNotBlank() }
    return when (d.state) {
        DownloadState.DONE ->
            listOfNotNull(artist, formatBytes(d.bytesDownloaded)).joinToString(" · ")
        DownloadState.DOWNLOADING ->
            d.progressFraction?.let { "Downloading ${(it * 100).toInt()}%" }
                ?: "Downloading…"
        DownloadState.QUEUED -> "Queued"
        DownloadState.FAILED -> "Download failed — tap to retry"
    }
}

/** Byte count → display string ("412 KB", "3.2 MB", "1.1 GB"). */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

internal fun DownloadItem.matchesLibraryQuery(query: String): Boolean =
    title.containsQuery(query) || artist.containsQuery(query)

/** Downloaded row → single-entry queue (same stream mediaId it plays as). */
private fun DownloadItem.toQueueItem(): QueueItem = QueueItem(
    position = 0,
    mediaId = mediaId,
    title = title,
    artist = artist,
    artworkUri = artworkUri,
    source = MediaSource.YOUTUBE,
    durationMs = null,
    addedAt = System.currentTimeMillis(),
)

// --- "find in library" filtering -------------------------------------------

private fun String?.containsQuery(query: String): Boolean =
    this?.contains(query, ignoreCase = true) == true

internal fun Disc.matchesLibraryQuery(query: String): Boolean =
    title.containsQuery(query) ||
        performer.containsQuery(query) ||
        composer.containsQuery(query)

internal fun Track.matchesLibraryQuery(query: String): Boolean =
    title.containsQuery(query) ||
        performer.containsQuery(query) ||
        composer.containsQuery(query)

internal fun QueueItem.matchesLibraryQuery(query: String): Boolean =
    title.containsQuery(query) || artist.containsQuery(query)

internal fun FavoriteItem.matchesLibraryQuery(query: String): Boolean =
    title.containsQuery(query) || artist.containsQuery(query)

internal fun Playlist.matchesLibraryQuery(query: String): Boolean =
    name.containsQuery(query)

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
