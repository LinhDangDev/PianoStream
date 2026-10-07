package com.pianostream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.pianostream.app.navigation.AppNav
import com.pianostream.app.navigation.Route
import com.pianostream.app.scaffold.AppScaffold
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
            PianoStreamTheme {
                // Back stack hoisted above the scaffold so the mini player
                // survives tab switches (plan C1).
                val backStack = rememberNavBackStack(Route.Home)
                // Same activity-scoped instance AppScaffold uses (C1).
                val appViewModel: AppViewModel = hiltViewModel()
                AppScaffold(backStack = backStack) {
                    AppNav(
                        backStack = backStack,
                        onPlayQueue = { items, start ->
                            appViewModel.playQueue(items, start)
                            backStack.add(Route.NowPlaying)
                        },
                    )
                }
            }
        }
    }
}
