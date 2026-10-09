package com.pianostream.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.designsystem.art.ArtworkUrls
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora

/**
 * Spotify-style "Top result" hero — the first result promoted to a card:
 * bigger artwork, bold title, kind label and a gold play button
 * (reference: Spotify Search results page).
 */
@Composable
fun TopResultCard(
    title: String,
    subtitle: String?,
    /** Kind label — "Song", "Video", "Album", "Artist", "Playlist". */
    typeLabel: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Aurora.SurfaceLight)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Aurora.Bg),
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
                VinylDisc(
                    artworkUri = null,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    labelRatio = 0.42f,
                    artworkContentDescription = null,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Vinyl.GoldDim,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Aurora.TextPrimary,
                maxLines = 2,
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
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Vinyl.Gold)
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play $title",
                tint = Aurora.Bg,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
