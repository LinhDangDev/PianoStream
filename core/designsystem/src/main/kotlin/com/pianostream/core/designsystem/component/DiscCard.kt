package com.pianostream.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pianostream.core.designsystem.theme.Aurora

/**
 * A disc in the grid, drawn as a vinyl record on a neumorphic charcoal
 * card — the same turntable language as the player.
 *
 * [isSingle] marks a loose file (one-track disc, no .cue): the record
 * gets a 7-inch look — proportionally larger centre label plus a gold
 * "45" stamp — instead of reading like a broken album.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscCard(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press context action (disc ⋯ sheet); null = disabled. */
    onLongClick: (() -> Unit)? = null,
    isSingle: Boolean = false,
) {
    // Action-first description so a11y announces intent, and the E2E suite
    // can target the card without relying on dynamic titles.
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .semantics {
                contentDescription = "Open disc: $title" +
                    if (isSingle) ", single" else ""
            }
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF2A2D32),
                        Color(0xFF1B1C1F),
                    ),
                ),
            )
            .border(0.5.dp, Aurora.Hairline, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            VinylDisc(
                artworkUri,
                title,
                Modifier.fillMaxSize(),
                // A real 45 has an oversized centre label — echo it.
                labelRatio = if (isSingle) SINGLE_LABEL_RATIO else Vinyl.LABEL_RATIO,
            )
            if (isSingle) {
                SingleBadge(Modifier.align(Alignment.TopStart))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Aurora.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A 45's centre label is far bigger relative to the record than an LP's. */
const val SINGLE_LABEL_RATIO = 0.46f

/**
 * The "45" stamp: a small champagne-on-charcoal marker for a loose-file
 * single (a 7-inch, not an album). Announced as "Single" to a11y.
 */
@Composable
fun SingleBadge(modifier: Modifier = Modifier, diameter: Dp = 26.dp) {
    Box(
        modifier
            .size(diameter)
            .semantics { contentDescription = "Single" }
            .clip(CircleShape)
            .background(Color(0xE6202125))
            .border(1.dp, Vinyl.Gold.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "45",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Vinyl.Gold,
            maxLines = 1,
        )
    }
}
