package com.pianostream.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora

/**
 * foss dist: the self-updater does not exist (F-Droid policy, R5) — this
 * destination is an inert "not available" surface and the Settings row
 * pointing here is a dimmed no-op. Zero updater code compiles into foss.
 *
 * Presentation: same neumorphic card as the github surface but dimmed —
 * the vinyl glyph and gold are muted to read as unavailable (cosmetic
 * only; no updater behaviour here either).
 */
@Composable
internal fun UpdaterOptInContent() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(12.dp, shape, clip = false)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2A2D32), Color(0xFF1B1C1F)),
                    ),
                )
                .border(0.5.dp, Aurora.Hairline, shape)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VinylDisc(
                artworkUri = null,
                title = "GitHub builds only",
                modifier = Modifier
                    .size(96.dp)
                    .alpha(0.55f),
                artworkContentDescription = null,
            )
            Text(
                "GitHub builds only",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Vinyl.GoldDim,
            )
            Text(
                "In-app updates are not included in this build.",
                style = MaterialTheme.typography.bodyMedium,
                color = Aurora.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
