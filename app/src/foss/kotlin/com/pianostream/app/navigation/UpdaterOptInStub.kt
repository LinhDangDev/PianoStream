package com.pianostream.app.navigation

import androidx.compose.runtime.Composable
import com.pianostream.core.designsystem.component.EmptyState

/**
 * foss dist: the self-updater does not exist (F-Droid policy, R5) — this
 * destination is an inert "not available" surface and the Settings row
 * pointing here is a dimmed no-op. Zero updater code compiles into foss.
 */
@Composable
internal fun UpdaterOptInContent() {
    EmptyState(
        title = "GitHub builds only",
        body = "In-app updates are not included in this build.",
    )
}
