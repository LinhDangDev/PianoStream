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
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.feature.search.SearchScreen
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
    @Serializable public data object Settings : Route
    @Serializable public data object About : Route
    /** Bound per-dist: github → real updater screen, foss → no-op (R5). */
    @Serializable public data object UpdaterOptIn : Route
}

/** Root NavDisplay. The back stack is hoisted in `MainActivity` (C1). */
@Composable
public fun AppNav(
    backStack: NavBackStack<NavKey>,
    /** "Play these rows at position" — MainActivity routes it to AppViewModel.playQueue. */
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    /** "Append this row to the queue" — MainActivity routes it to AppViewModel.enqueue. */
    onEnqueue: (QueueItem) -> Unit,
) {
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
                SearchScreen(onPlayQueue = onPlayQueue, onEnqueue = onEnqueue)
            }
            entry<Route.Library> {
                LibraryScreen(onPlayQueue = onPlayQueue)
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
}

// `UpdaterOptInContent()` is bound per-dist: implemented in `app/src/foss`
// (no-op stub — zero updater code in foss builds, R5) and `app/src/github`
// (Phase-5b updater screen; placeholder until then). Same signature, one
// per flavor source set, so exactly one implementation compiles in.
//
// The player has no route: the ExpandingPlayer island in AppScaffold is the
// single now-playing surface (dock -> expanded morph), and its queue lives
// in a ModalBottomSheet inside the island.
