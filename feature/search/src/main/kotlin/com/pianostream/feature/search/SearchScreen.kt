package com.pianostream.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.data.downloads.DownloadItem
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.TrackRow
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora
import com.pianostream.core.youtube.api.YouTubeItem

/**
 * Search — submit-only input (IME Search/Enter runs the query; typing never
 * hits backends), scope chips (All / This device / YouTube) plus a second
 * chip row filtering results by kind (All / Songs / Videos / Artists /
 * Albums / Playlists). An empty field shows recent searches; results render
 * Spotify-style — a "Top result" hero then "This device" and per-kind YouTube
 * sections capped at four rows with "See all" applying the kind's chip.
 * Suggestions only while `sendSearchSuggestions` + YouTube scope; the YouTube
 * section stays hidden behind the first-run disclosure (F12). The trailing
 * "…" on each row is the [onActions] mount — the integrator hangs the
 * track-actions sheet on it.
 */
@Composable
fun SearchScreen(
    /** Play a queue: rows + start position (C6 — app layer owns the player). */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    /** Append one row to the end of the current queue ("add to queue"). */
    onEnqueue: (QueueItem) -> Unit,
    /** Row "…" menu slot — rendered only when provided (INTEGRATION). */
    onActions: ((SearchViewModel.ResultRow) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            placeholder = { Text("Search discs, tracks, YouTube…") },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Aurora.TextSecondary)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = Vinyl.GoldDim,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { viewModel.clearQuery() },
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
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

        // Kind chips — filter the rendered sections and rows by result type.
        // Six chips overflow portrait width, so the row scrolls.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            for (filter in SearchViewModel.TypeFilter.entries) {
                FilterChip(
                    selected = state.typeFilter == filter,
                    onClick = { viewModel.setTypeFilter(filter) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF1E1F23),
                        labelColor = Aurora.TextSecondary,
                        selectedContainerColor = Vinyl.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = Vinyl.Gold,
                    ),
                    label = { Text(filter.label()) },
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
                        onClick = { viewModel.submit(s) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF1E1F23),
                            labelColor = Aurora.TextSecondary,
                        ),
                        label = { Text(s, maxLines = 1) },
                    )
                }
            }
        }

        // Rows without kind info (local hits) count as Songs under the filter.
        val visibleLocal = if (state.typeFilter == SearchViewModel.TypeFilter.ALL ||
            state.typeFilter == SearchViewModel.TypeFilter.SONGS
        ) {
            state.localHits
        } else {
            emptyList()
        }
        val visibleYoutube = state.youtubeItems.filter { it.kind.matches(state.typeFilter) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            // Recent searches — local data, allowed regardless of localOnly.
            if (state.query.isBlank() && state.recentQueries.isNotEmpty()) {
                item(key = "recents-header") {
                    ResultSectionHeader(
                        title = "Recent searches",
                        actionLabel = "Clear all",
                        onAction = viewModel::clearRecents,
                    )
                }
                items(state.recentQueries, key = { "recent:$it" }) { recent ->
                    RecentSearchRow(
                        query = recent,
                        onClick = { viewModel.submit(recent) },
                        onRemove = { viewModel.removeRecent(recent) },
                    )
                }
            }

            // The field holds unsubmitted text — tell the user how it runs.
            if (state.query.isNotBlank() && state.query != state.submittedQuery &&
                !state.searching
            ) {
                item(key = "submit-hint") {
                    Text(
                        "Press ⏎ to search for “${state.query}”",
                        color = Aurora.TextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }

            if (visibleLocal.isEmpty() && visibleYoutube.isEmpty() &&
                state.submittedQuery.isNotBlank() && !state.searching
            ) {
                item {
                    EmptyState(
                        title = "No results",
                        body = if (state.typeFilter == SearchViewModel.TypeFilter.ALL) {
                            "Nothing in your library matches “${state.submittedQuery}”."
                        } else {
                            "No ${state.typeFilter.label()} results for “${state.submittedQuery}”."
                        },
                    )
                }
            }

            // "Top result" hero — the first result overall (device first).
            if (state.typeFilter == SearchViewModel.TypeFilter.ALL) {
                val topLocal = visibleLocal.firstOrNull()
                val topYoutube = visibleYoutube.firstOrNull()
                if (topLocal != null || topYoutube != null) {
                    item { SectionHeader("Top result") }
                    item(key = "top-result") {
                        when {
                            topLocal != null -> TopResultCard(
                                title = topLocal.title,
                                subtitle = topLocal.snippet,
                                typeLabel = "Song",
                                artworkUri = null,
                                onClick = {
                                    scope.launch {
                                        viewModel.mediaIdFor(topLocal)?.let { mediaId ->
                                            onPlayQueue(listOf(topLocal.toQueueItem(mediaId)), 0)
                                        }
                                    }
                                },
                            )
                            topYoutube != null -> TopResultCard(
                                title = topYoutube.title,
                                subtitle = topYoutube.subtitle,
                                typeLabel = topYoutube.kind.singularLabel(),
                                artworkUri = topYoutube.artworkUri,
                                onClick = {
                                    topYoutube.videoId?.let { vid ->
                                        onPlayQueue(listOf(topYoutube.toQueueItem(vid)), 0)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (visibleLocal.isNotEmpty()) {
                item { SectionHeader("This device") }
                items(visibleLocal, key = { "local:${it.trackId}" }) { hit ->
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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                hitMediaId?.let { id ->
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = "Add to queue",
                                        tint = Vinyl.GoldDim,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clickable {
                                                onEnqueue(hit.toQueueItem(id))
                                                Toast.makeText(
                                                    context, "Added to queue", Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                    )
                                }
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
                                if (onActions != null) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "More actions",
                                        tint = Vinyl.GoldDim,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clickable {
                                                onActions(
                                                    SearchViewModel.ResultRow(
                                                        mediaId = hitMediaId,
                                                        title = hit.title,
                                                        subtitle = hit.snippet,
                                                        artworkUri = null,
                                                        durationMs = null,
                                                        local = true,
                                                        error = null,
                                                    ),
                                                )
                                            },
                                    )
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
                            TextButton(
                                onClick = { viewModel.acceptYoutubeDisclosure() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                            ) {
                                Text("Enable YouTube search")
                            }
                        }
                    }
                } else {
                    // A failed call must not read as "No results".
                    if (state.youtubeError && state.youtubeItems.isEmpty() && !state.searching) {
                        item {
                            EmptyState(
                                title = "Couldn't reach YouTube",
                                body = "Check your connection, then try again.",
                            )
                        }
                    }
                    val sections = listOf(
                        YouTubeItem.Kind.TRACK,
                        YouTubeItem.Kind.VIDEO,
                        YouTubeItem.Kind.ARTIST,
                        YouTubeItem.Kind.ALBUM,
                        YouTubeItem.Kind.PLAYLIST,
                    )
                    for (kind in sections) {
                        val kindItems = visibleYoutube.filter { it.kind == kind }
                        if (kindItems.isEmpty()) continue
                        val allView = state.typeFilter == SearchViewModel.TypeFilter.ALL
                        val rows = if (allView) kindItems.take(4) else kindItems
                        item(key = "section-${kind.name}") {
                            ResultSectionHeader(
                                title = when (kind) {
                                    YouTubeItem.Kind.TRACK -> "Songs"
                                    YouTubeItem.Kind.VIDEO -> "Videos"
                                    YouTubeItem.Kind.ARTIST -> "Artists"
                                    YouTubeItem.Kind.ALBUM -> "Albums"
                                    YouTubeItem.Kind.PLAYLIST -> "Playlists"
                                },
                                actionLabel = if (allView && kindItems.size > 4) "See all" else null,
                                onAction = { viewModel.setTypeFilter(kind.toTypeFilter()) },
                            )
                        }
                        items(rows, key = { "yt:${kind.name}:${it.videoId ?: it.title}" }) { item ->
                            val favMediaId = item.videoId?.let(PianoStreamUri::stream)
                            TrackRow(
                                title = item.title,
                                subtitle = item.subtitle,
                                artworkUri = item.artworkUri,
                                durationMs = item.durationMs,
                                onClick = {
                                    when {
                                        // PLAYLIST row → "Play all": expand
                                        // the list, queue every track.
                                        item.kind == YouTubeItem.Kind.PLAYLIST -> {
                                            scope.launch {
                                                val rows = viewModel
                                                    .playlistTracks(item.browseId)
                                                    .mapNotNull { t ->
                                                        t.videoId?.let { vid -> t.toQueueItem(vid) }
                                                    }
                                                if (rows.isEmpty()) {
                                                    Toast.makeText(
                                                        context,
                                                        "No playable tracks in this playlist",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    onPlayQueue(rows, 0)
                                                }
                                            }
                                        }
                                        else -> item.videoId?.let { vid ->
                                            onPlayQueue(listOf(item.toQueueItem(vid)), 0)
                                        }
                                    }
                                },
                                trailing = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (favMediaId != null) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                                contentDescription = "Add to queue",
                                                tint = Vinyl.GoldDim,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clickable {
                                                        item.videoId?.let { vid ->
                                                            onEnqueue(item.toQueueItem(vid))
                                                            Toast.makeText(
                                                                context, "Added to queue", Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    },
                                            )
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
                                            item.videoId?.let { vid ->
                                                DownloadAffordance(
                                                    download = state.downloads[vid],
                                                    onStart = {
                                                        viewModel.enqueueDownload(
                                                            vid, item.title, item.subtitle, item.artworkUri,
                                                        )
                                                    },
                                                    onRemove = { viewModel.removeDownload(vid) },
                                                    onPlayDone = {
                                                        onPlayQueue(listOf(item.toQueueItem(vid)), 0)
                                                    },
                                                )
                                            }
                                        }
                                        if (onActions != null) {
                                            Icon(
                                                imageVector = Icons.Filled.MoreVert,
                                                contentDescription = "More actions",
                                                tint = Vinyl.GoldDim,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clickable {
                                                        onActions(
                                                            SearchViewModel.ResultRow(
                                                                mediaId = favMediaId,
                                                                title = item.title,
                                                                subtitle = item.subtitle,
                                                                artworkUri = item.artworkUri,
                                                                durationMs = item.durationMs,
                                                                local = false,
                                                                error = null,
                                                            ),
                                                        )
                                                    },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            } else if (state.scope == SearchViewModel.Scope.YOUTUBE) {
                // The "This device only" setting hides YouTube entirely —
                // say so instead of silently showing "No results".
                item { SectionHeader("YouTube") }
                item {
                    EmptyState(
                        title = "YouTube search is off",
                        body = "Turn off ‘This device only’ in Settings to stream YouTube Music.",
                    )
                }
            }
        }
    }
}

/**
 * Download icon affordance on a YouTube row — the Spotify/YouTube Music
 * state machine: arrow-down → progress ring → filled check. Tap when done
 * plays the track; long-press removes the download in any state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadAffordance(
    download: DownloadItem?,
    onStart: () -> Unit,
    onRemove: () -> Unit,
    onPlayDone: () -> Unit,
) {
    val onClick = when (download?.state) {
        null, DownloadState.FAILED -> onStart
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> onRemove
        DownloadState.DONE -> onPlayDone
    }
    val description = when (download?.state) {
        null -> "Download"
        DownloadState.QUEUED -> "Queued — tap to cancel, long-press to remove"
        DownloadState.DOWNLOADING -> "Downloading — tap to cancel, long-press to remove"
        DownloadState.DONE -> "Downloaded — tap to play, long-press to remove"
        DownloadState.FAILED -> "Download failed — tap to retry, long-press to remove"
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (download != null) onRemove else null,
            ),
    ) {
        when (download?.state) {
            DownloadState.QUEUED -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Vinyl.Gold,
            )
            DownloadState.DOWNLOADING -> {
                val fraction = download.progressFraction
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Vinyl.Gold,
                        trackColor = Aurora.Hairline,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Vinyl.Gold,
                    )
                }
            }
            DownloadState.DONE -> Icon(
                imageVector = Icons.Filled.DownloadDone,
                contentDescription = description,
                tint = Vinyl.Gold,
                modifier = Modifier.size(22.dp),
            )
            DownloadState.FAILED -> Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = description,
                tint = Vinyl.Gold,
                modifier = Modifier.size(22.dp),
            )
            null -> Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = description,
                tint = Vinyl.GoldDim,
                modifier = Modifier.size(22.dp),
            )
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

private fun YouTubeItem.toQueueItem(
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
