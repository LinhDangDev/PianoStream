package com.pianostream.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.pianostream.app.about.AboutScreen
import com.pianostream.app.settings.SettingsScreen
import com.pianostream.feature.home.HomeScreen
import com.pianostream.feature.library.LibraryScreen
import com.pianostream.feature.player.NowPlayingScreen
import com.pianostream.feature.player.QueueSheet
import com.pianostream.feature.search.SearchScreen

/** App destinations (Navigation3 keys). */
public sealed interface Route : NavKey {
    public data object Home : Route
    public data object Search : Route
    public data object Library : Route
    public data object NowPlaying : Route
    public data object Queue : Route
    public data object Settings : Route
    public data object About : Route
    /** Bound per-dist: github → real updater screen, foss → no-op (R5). */
    public data object UpdaterOptIn : Route
}

/** Root NavDisplay. The back stack is hoisted in `MainActivity` (C1). */
@Composable
public fun AppNav(backStack: NavBackStack<NavKey>) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Route.Home> {
                HomeScreen(
                    onOpenDisc = { backStack.add(Route.Library) },
                    onOpenTrack = { backStack.add(Route.NowPlaying) },
                )
            }
            entry<Route.Search> {
                SearchScreen(onOpenTrack = { backStack.add(Route.NowPlaying) })
            }
            entry<Route.Library> {
                LibraryScreen(
                    onOpenTrack = { backStack.add(Route.NowPlaying) },
                )
            }
            entry<Route.NowPlaying> {
                NowPlayingScreen(
                    palette = null,
                    onOpenQueue = { backStack.add(Route.Queue) },
                )
            }
            entry<Route.Queue> { QueueSheet() }
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
}

// `UpdaterOptInContent()` is bound per-dist: implemented in `app/src/foss`
// (no-op stub — zero updater code in foss builds, R5) and `app/src/github`
// (Phase-5b updater screen; placeholder until then). Same signature, one
// per flavor source set, so exactly one implementation compiles in.
