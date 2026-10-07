package com.pianostream.app.navigation

import androidx.compose.runtime.Composable
import com.pianostream.core.designsystem.component.EmptyState

/**
 * github dist placeholder — Phase 5b replaces this with the real updater
 * opt-in screen (signed-APK download + minisign verification flow). Kept so
 * the github flavor compiles today.
 */
@Composable
internal fun UpdaterOptInContent() {
    EmptyState(
        title = "Updater",
        body = "In-app updates will arrive in the release build.",
    )
}
