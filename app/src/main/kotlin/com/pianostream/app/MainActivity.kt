package com.pianostream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.app.navigation.AppNav
import com.pianostream.app.navigation.Route
import com.pianostream.app.scaffold.AppScaffold
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.designsystem.theme.PianoStreamTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Host activity: edge-to-edge (API 36 default behavior; insets consumed by
 * the scaffold), predictive back via `enableOnBackInvokedCallback` in the
 * manifest + Navigation3's back handling.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Same activity-scoped instance AppScaffold uses (C1). Reading
            // theme prefs here finally wires the Settings toggles through
            // (they were dead until now).
            val appViewModel: AppViewModel = hiltViewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by appViewModel.dynamicColor.collectAsStateWithLifecycle()
            PianoStreamTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                },
                dynamicColor = dynamicColor,
            ) {
                // Back stack hoisted above the scaffold so the player dock
                // survives tab switches (plan C1).
                val backStack = rememberNavBackStack(Route.Home)
                AppScaffold(backStack = backStack) {
                    AppNav(
                        backStack = backStack,
                        onPlayQueue = { items, start ->
                            // Play + stay put: the dock appears; tapping it
                            // morphs into the full player (Aurora pattern).
                            appViewModel.playQueue(items, start)
                        },
                        onEnqueue = { item -> appViewModel.enqueue(listOf(item)) },
                    )
                }
            }
        }
    }
}
