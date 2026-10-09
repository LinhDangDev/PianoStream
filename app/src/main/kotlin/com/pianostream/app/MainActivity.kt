package com.pianostream.app

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.app.navigation.AppNav
import com.pianostream.app.navigation.Route
import com.pianostream.app.scaffold.AppScaffold
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.data.uri.PianoStreamUri
import com.pianostream.core.designsystem.theme.PianoStreamTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Host activity: edge-to-edge (API 36 default behavior; insets consumed by
 * the scaffold), predictive back via `enableOnBackInvokedCallback` in the
 * manifest + Navigation3's back handling.
 *
 * Picture-in-Picture (Now Playing spec): the manifest sets
 * `supportsPictureInPicture`, and [onUserLeaveHint] auto-enters PiP at 16:9
 * when the user leaves while a `pianostream://video/` item is playing —
 * YouTube Music's behaviour; audio playback never triggers it. Playback is
 * service-backed (`PlaybackService`, FGS `mediaPlayback`), so nothing here
 * pauses on stop and frames keep flowing to `VideoPanel`'s TextureView while
 * the compose tree renders inside the PiP window. Hiding the non-video
 * chrome needs a seam in feature/player (see INTEGRATION notes in the
 * progress log) — per spec, keep rendering is the accepted minimum.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    // Activity-scoped instance — the same object `hiltViewModel()` resolves
    // inside the compose tree and in `AppScaffold` (C1). Held as a field so
    // `onUserLeaveHint` can reach the live player outside composition.
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Reading theme prefs here finally wires the Settings toggles
            // through (they were dead until now).
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfVideoPlaying()
    }

    /**
     * YouTube Music rule: auto-PiP only when the CURRENT item renders video
     * — a `pianostream://video/` mediaId ([PianoStreamUri.isVideo]); Song-mode
     * streams and local discs never qualify — and it is actually playing.
     * `runCatching` covers devices/builds where PiP is unavailable or
     * disallowed: `enterPictureInPictureMode` throws `IllegalStateException`
     * rather than returning a status.
     */
    private fun enterPipIfVideoPlaying() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val player = appViewModel.player.value ?: return
        if (!player.isPlaying) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (!PianoStreamUri.isVideo(mediaId)) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }
}
