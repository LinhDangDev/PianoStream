package com.pianostream.app.scaffold

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.material3.MiniController
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.pianostream.app.AppViewModel
import com.pianostream.app.navigation.Route

private val TOP_LEVEL = listOf(
    Triple(Route.Home, "Home", Icons.Outlined.Home),
    Triple(Route.Search, "Search", Icons.Outlined.Search),
    Triple(Route.Library, "Library", Icons.Outlined.LibraryMusic),
)

/**
 * Root scaffold: bottom nav for the three tabs + the mini player between
 * content and nav bar (mini player survives tab switches because the back
 * stack is hoisted at `MainActivity`, C1).
 */
@Composable
public fun AppScaffold(
    backStack: NavBackStack<NavKey>,
    viewModel: AppViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.connectIfNeeded() }

    val current = backStack.lastOrNull()
    val showTabs = current in TOP_LEVEL.map { it.first }

    Scaffold(
        bottomBar = {
            Column {
                val p = player
                if (p != null && showTabs) {
                    MiniController(
                        player = p,
                        onClick = { backStack.add(Route.NowPlaying) },
                    )
                }
                if (showTabs) {
                    NavigationBar {
                        TOP_LEVEL.forEach { (route, label, icon) ->
                            NavigationBarItem(
                                selected = current == route,
                                onClick = {
                                    // Tab switch: collapse to root, then select.
                                    while (backStack.size > 1) {
                                        backStack.removeLastOrNull()
                                    }
                                    if (backStack.lastOrNull() != route) {
                                        backStack.add(route)
                                    }
                                },
                                icon = {
                                    Icon(
                                        icon,
                                        contentDescription = "$label tab",
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(Modifier.padding(padding)) {
            content()
        }
    }
}
