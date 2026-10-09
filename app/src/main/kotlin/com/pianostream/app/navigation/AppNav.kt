package com.pianostream.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.pianostream.app.AppViewModel
import com.pianostream.app.about.AboutScreen
import com.pianostream.app.settings.SettingsScreen
import com.pianostream.app.share.ShareIntents
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.feature.home.HomeScreen
import com.pianostream.feature.library.LibraryScreen
import com.pianostream.feature.library.LibraryViewModel
import com.pianostream.feature.library.artist.ArtistScreen
import com.pianostream.feature.player.actions.TrackAction
import com.pianostream.feature.player.actions.TrackActionsSheet
import com.pianostream.feature.search.SearchScreen
import com.pianostream.feature.search.SearchViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** App destinations (Navigation3 keys). */
// Nav3 serializes the back stack for state save/restore — every key must
// be @Serializable (incl. the objects; the sealed interface gives the
// polymorphic serializer).
@Serializable
public sealed interface Route : NavKey {
    @Serializable public data object Home : Route
    @Serializable public data object Search : Route
    @Serializable public data object Library : Route
    @Serializable public data class Artist(val artistName: String) : Route
    @Serializable public data object Settings : Route
    @Serializable public data object About : Route
    /** Bound per-dist: github → real updater screen, foss → no-op (R5). */
    @Serializable public data object UpdaterOptIn : Route
}

/** A track-ish row the actions sheet can act on (search hit or library row). */
private data class ActionTarget(
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    /** QueueItem for mediaId-bearing actions; null = share/goto-artist only. */
    val item: QueueItem?,
    val artist: String?,
    val discId: Long?,
)

private fun SearchViewModel.ResultRow.toTarget(): ActionTarget = ActionTarget(
    title = title,
    subtitle = subtitle.orEmpty(),
    artworkUri = artworkUri,
    item = mediaId?.let {
        QueueItem(
            position = 0,
            mediaId = it,
            title = title,
            artist = subtitle,
            artworkUri = artworkUri,
            source = if (local) MediaSource.LOCAL else MediaSource.YOUTUBE,
            durationMs = durationMs,
            addedAt = System.currentTimeMillis(),
        )
    },
    artist = subtitle,
    discId = null,
)

private fun Track.toTarget(): ActionTarget = ActionTarget(
    title = title,
    subtitle = performer ?: composer ?: "",
    artworkUri = null,
    // Track rows carry no mediaId — a derived key would need the disc's
    // folderPath + cue index; only discId-bound and share-by-text actions
    // are enabled for these rows.
    item = null,
    artist = performer ?: composer,
    discId = discId,
)

/** Root NavDisplay. The back stack is hoisted in `MainActivity` (C1). */
@Composable
public fun AppNav(
    backStack: NavBackStack<NavKey>,
    /** "Play these rows at position" — MainActivity routes it to AppViewModel.playQueue. */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    /** "Append this row to the queue" — MainActivity routes it to AppViewModel.enqueue. */
    onEnqueue: (QueueItem) -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var actionsTarget by remember { mutableStateOf<ActionTarget?>(null) }
    var actionsFavorite by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<QueueItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<Route.Home> {
                    HomeScreen(
                        onOpenDisc = { backStack.add(Route.Library) },
                        onPlayQueue = onPlayQueue,
                        onOpenSettings = { backStack.add(Route.Settings) },
                    )
                }
                entry<Route.Search> {
                    SearchScreen(
                        onPlayQueue = onPlayQueue,
                        onEnqueue = onEnqueue,
                        onActions = { row -> actionsTarget = row.toTarget() },
                    )
                }
                entry<Route.Library> {
                    val libVm: LibraryViewModel = hiltViewModel()
                    val pending by viewModel.pendingDisc.collectAsState()
                    LaunchedEffect(pending) {
                        pending?.let { libVm.selectDisc(it) }
                        if (pending != null) viewModel.consumePendingDisc()
                    }
                    LibraryScreen(
                        onPlayQueue = onPlayQueue,
                        onOpenArtist = { backStack.add(Route.Artist(it)) },
                        onTrackActions = { track ->
                            actionsTarget = track.toTarget()
                        },
                        onDiscAction = { disc, action ->
                            when (action) {
                                com.pianostream.feature.library.DiscAction.Play ->
                                    libVm.queueForDisc(disc) { rows ->
                                        onPlayQueue(rows, 0)
                                    }
                                com.pianostream.feature.library.DiscAction.Shuffle ->
                                    libVm.queueForDisc(disc) { rows ->
                                        onPlayQueue(rows, 0)
                                        viewModel.setShuffle(true)
                                    }
                                com.pianostream.feature.library.DiscAction.AddToQueue ->
                                    libVm.queueForDisc(disc) { rows ->
                                        viewModel.enqueue(rows)
                                    }
                                com.pianostream.feature.library.DiscAction.GoToArtist ->
                                    (disc.performer ?: disc.composer)?.let {
                                        backStack.add(Route.Artist(it))
                                    }
                                com.pianostream.feature.library.DiscAction
                                    .AddToPlaylist -> Unit
                            }
                        },
                        viewModel = libVm,
                    )
                }
                entry<Route.Artist> { key ->
                    ArtistScreen(
                        artistName = key.artistName,
                        onBack = { backStack.removeLastOrNull() },
                        onPlayTracks = onPlayQueue,
                        onOpenDisc = { disc ->
                            viewModel.pendingDisc.value = disc
                            backStack.add(Route.Library)
                        },
                    )
                }
                entry<Route.Settings> {
                    SettingsScreen(
                        onOpenAbout = { backStack.add(Route.About) },
                        onOpenUpdater = { backStack.add(Route.UpdaterOptIn) },
                    )
                }
                entry<Route.About> { AboutScreen() }
                entry<Route.UpdaterOptIn> { UpdaterOptInContent() }
            },
        )

        // ---- Row-level sheets (mounted at nav level so they overlay the
        // current destination; the player's own ⋯ sheet lives in
        // ExpandingPlayer). -----------------------------------------------
        actionsTarget?.let { target ->
            val scope = rememberCoroutineScope()
            LaunchedEffect(target) {
                actionsFavorite =
                    target.item?.mediaId?.let { viewModel.isFavorite(it) }
                        ?: false
            }
            TrackActionsSheet(
                title = target.title,
                subtitle = target.subtitle,
                artworkUri = target.artworkUri,
                isFavorite = actionsFavorite,
                enabled = buildSet {
                    add(TrackAction.Share)
                    target.artist?.let { add(TrackAction.GoToArtist) }
                    if (target.discId != null) add(TrackAction.GoToAlbum)
                    if (target.item != null) {
                        add(TrackAction.ToggleFavorite)
                        add(TrackAction.AddToPlaylist)
                        add(TrackAction.AddToQueue)
                    }
                },
                onAction = { action ->
                    val item = target.item
                    when (action) {
                        TrackAction.ToggleFavorite ->
                            item?.let { viewModel.toggleFavorite(it) }
                        TrackAction.AddToPlaylist ->
                            item?.let { playlistTarget = it }
                        TrackAction.AddToQueue -> item?.let { onEnqueue(it) }
                        TrackAction.GoToAlbum -> target.discId?.let { id ->
                            scope.launch {
                                viewModel.discById(id)?.let { disc ->
                                    viewModel.pendingDisc.value = disc
                                    backStack.add(Route.Library)
                                }
                            }
                        }
                        TrackAction.GoToArtist -> target.artist?.let {
                            backStack.add(Route.Artist(it))
                        }
                        TrackAction.Share -> ShareIntents.shareTrack(
                            context = context,
                            title = target.title,
                            artist = target.artist,
                            sourceUri = item?.mediaId,
                            artworkUri = target.artworkUri,
                        )
                        TrackAction.SleepTimer -> Unit
                    }
                    actionsTarget = null
                },
                onDismiss = { actionsTarget = null },
            )
        }

        playlistTarget?.let { item ->
            PlayerPlaylistSheet(
                item = item,
                viewModel = viewModel,
                onDismiss = { playlistTarget = null },
            )
        }
    }
}

/** Data-driven host for the library's add-to-playlist sheet — collects the
 *  playlist list + memberships for [item] and forwards the callbacks.
 *  Shared by nav-level row sheets and the player ⋯ sheet (AppScaffold). */
@Composable
public fun PlayerPlaylistSheet(
    item: QueueItem,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val playlists by viewModel.observePlaylists()
        .collectAsState(emptyList())
    val containing by remember(item.mediaId) {
        viewModel.playlistsContaining(item.mediaId)
    }.collectAsState(emptyList())
    com.pianostream.feature.library.AddToPlaylistSheet(
        playlists = playlists,
        containing = containing,
        onPick = { viewModel.addToPlaylist(it.id, item) },
        onRemove = { viewModel.removeFromPlaylist(it.id, item.mediaId) },
        onClearAll = { viewModel.removeFromAllPlaylists(item.mediaId) },
        onNew = { viewModel.createPlaylistAndAdd(it, item) },
        onDismiss = onDismiss,
    )
}

// `UpdaterOptInContent()` is bound per-dist: implemented in `app/src/foss`
// (no-op stub — zero updater code in foss builds, R5) and `app/src/github`
// (Phase-5b updater screen; placeholder until then). Same signature, one
// per flavor source set, so exactly one implementation compiles in.
//
// The player has no route: the ExpandingPlayer island in AppScaffold is the
// single now-playing surface (dock -> expanded morph), and its queue lives
// in a ModalBottomSheet inside the island.
