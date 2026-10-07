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
import com.pianostream.feature.player.ExpandingPlayer

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
    val current = backStack.lastOrNull()
    val showTabs = current in TOP_LEVEL.map { it.route }

    Column(Modifier.fillMaxSize().background(Aurora.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                content()
            }
            ExpandingPlayer(
                expanded = playerExpanded,
                onExpandedChange = { playerExpanded = it },
            )
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
