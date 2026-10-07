package com.pianostream.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.designsystem.art.ArtworkUrls

/** Square artwork card for the discs grid. */
@Composable
fun DiscCard(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Action-first description so a11y announces intent, and the E2E suite
    // can target the card without relying on dynamic titles.
    Card(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Open disc: $title" },
    ) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(ArtworkUrls.sanitize(artworkUri))
                    .build(),
                contentDescription = "Artwork for $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            Column(Modifier.padding(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
