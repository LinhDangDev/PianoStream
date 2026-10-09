package com.pianostream.app.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.pianostream.app.AppViewModel
import com.pianostream.app.navigation.Route
import com.pianostream.core.designsystem.component.PillNavBar
import com.pianostream.core.designsystem.component.PillNavItem
import com.pianostream.core.designsystem.theme.Aurora
import com.pianostream.core.data.playback.model.MediaSource
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.app.share.ShareIntents
import com.pianostream.feature.player.ExpandingPlayer
import com.pianostream.feature.player.PlayerActions

private data class TopTab(val route: Route, val label: String, val icon: ImageVector)

private val TOP_LEVEL = listOf(
    TopTab(Route.Home, "Home", Icons.Outlined.Home),
    TopTab(Route.Search, "Search", Icons.Outlined.Search),
    TopTab(Route.Library, "Library", Icons.Outlined.LibraryMusic),
)

/**
 * Root scaffold, Aurora layout (ported from Melody): a Column whose weighted
 * content box hosts the NavDisplay AND the ExpandingPlayer overlay, with the
 * PillNavBar as a sibling below — not a Scaffold bottomBar (Xiaomi/HyperOS
 * mis-measures bottomBar overlays). The bar hides while the player is
 * expanded so the player surface can go full-bleed.
 */
@Composable
public fun AppScaffold(
    backStack: NavBackStack<NavKey>,
    viewModel: AppViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.connectIfNeeded() }

    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var playlistTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<QueueItem?>(null)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = backStack.lastOrNull()
    // Bar stays visible on every route (sub-screens show no selected tab);
    // only the expanded player hides it.
    val showTabs = true

    // Player ⋯ sheet targets the LIVE media item — read metadata at tap
    // time (these lambdas only fire while an item is loaded).
    val playerActions = PlayerActions(
        onAddToQueue = { viewModel.enqueueCurrent() },
        onShare = {
            viewModel.player.value?.currentMediaItem?.let { item ->
                val meta = item.mediaMetadata
                ShareIntents.shareTrack(
                    context = context,
                    title = meta.title?.toString() ?: item.mediaId,
                    artist = meta.artist?.toString(),
                    sourceUri = item.mediaId,
                    artworkUri = meta.artworkUri?.toString(),
                )
            }
        },
        onGoToArtist = {
            viewModel.player.value?.currentMediaItem?.mediaMetadata
                ?.artist?.toString()?.takeIf { it.isNotBlank() }?.let {
                    backStack.add(Route.Artist(it))
                }
        },
        onAddToPlaylist = {
            viewModel.player.value?.currentMediaItem?.let { item ->
                val meta = item.mediaMetadata
                playlistTarget = QueueItem(
                    position = 0,
                    mediaId = item.mediaId,
                    title = meta.title?.toString() ?: item.mediaId,
                    artist = meta.artist?.toString(),
                    artworkUri = meta.artworkUri?.toString(),
                    source = if (item.mediaId.startsWith("pianostream://local/")) {
                        MediaSource.LOCAL
                    } else {
                        MediaSource.YOUTUBE
                    },
                    durationMs = null,
                    addedAt = System.currentTimeMillis(),
                )
            }
        },
        // onSleepTimer/onGoToAlbum stay null — ExpandingPlayer renders its
        // own sleep-timer sheet, and a playing item has no discId handle.
        // onDownload renders only for videoCapable (YouTube) items.
        onDownload = { viewModel.toggleCurrentDownload() },
    )

    Column(Modifier.fillMaxSize().background(Aurora.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                content()
            }
            ExpandingPlayer(
                expanded = playerExpanded,
                onExpandedChange = { playerExpanded = it },
                actions = playerActions,
            )
            playlistTarget?.let { item ->
                com.pianostream.app.navigation.PlayerPlaylistSheet(
                    item = item,
                    viewModel = viewModel,
                    onDismiss = { playlistTarget = null },
                )
            }
        }
        if (showTabs && !playerExpanded) {
            PillNavBar(
                items = TOP_LEVEL.map { PillNavItem(it.icon, it.label) },
                selectedIndex = TOP_LEVEL.indexOfFirst { it.route == current },
                onSelect = { index ->
                    // Tab switch: collapse to root, then select.
                    while (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    }
                    val route = TOP_LEVEL[index].route
                    if (backStack.lastOrNull() != route) {
                        backStack.add(route)
                    }
                },
            )
        }
    }
}
