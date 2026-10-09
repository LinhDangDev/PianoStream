package com.pianostream.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.designsystem.art.ArtworkUrls
import com.pianostream.core.designsystem.theme.Aurora
import java.util.Locale

/** One list row: artwork + title + subtitle + optional duration. */
@Composable
fun TrackRow(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    durationMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    explicit: Boolean = false,
    artworkShape: Shape = RoundedCornerShape(10.dp),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp) // ≥48dp touch target
            .semantics { contentDescription = "Play track: $title" }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(artworkShape)
                .background(Aurora.SurfaceLight),
        ) {
            val uri = ArtworkUrls.sanitize(artworkUri)
            if (uri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uri)
                        .build(),
                    contentDescription = "Artwork for $title",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No artwork: a mini record reads better than a grey tile.
                VinylDisc(
                    artworkUri = null,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    labelRatio = 0.42f,
                    artworkContentDescription = null,
                )
            }
            if (isPlaying || isPaused) {
                // Scrim keeps the bars legible over bright artwork.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Aurora.Bg.copy(alpha = 0.45f))
                )
                EqualizerBars(
                    animate = isPlaying && !isPaused,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .height(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) Aurora.Accent else Aurora.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (explicit) {
                    ExplicitBadge()
                }
            }
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
        if (durationMs != null) {
            Text(
                formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = Aurora.TextSecondary,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/** Small rounded "E" badge — Spotify-style explicit marker next to the title. */
@Composable
private fun ExplicitBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Aurora.SurfaceLight)
            .semantics { contentDescription = "Explicit" }
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            "E",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Aurora.TextSecondary,
        )
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
