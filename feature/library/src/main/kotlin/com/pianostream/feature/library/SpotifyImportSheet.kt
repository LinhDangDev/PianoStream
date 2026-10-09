package com.pianostream.feature.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.pianostream.core.designsystem.art.ArtworkUrls
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora
import com.pianostream.core.youtube.spotify.SpotifyPlaylistImporter

/**
 * "Import from Spotify" sheet (reference: Metrolist/InnerTune's paste-link
 * import): link field → live per-track match progress → summary → opens the
 * freshly written playlist (the parent selects it behind the sheet).
 *
 * Public playlists only, capped at [SpotifyPlaylistImporter.MAX_TRACKS]
 * tracks (the embed's own limit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyImportSheet(
    /** Opens the imported playlist's detail view (also called on Done). */
    onOpenPlaylist: (Long) -> Unit,
    onDismiss: () -> Unit,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The moment a playlist lands we select it, so dismissing the sheet
    // leaves the user looking at the imported playlist detail.
    LaunchedEffect(state.phase) {
        (state.phase as? SpotifyImportViewModel.Phase.Done)
            ?.let { onOpenPlaylist(it.playlistId) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Import from Spotify",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            when (val phase = state.phase) {
                is SpotifyImportViewModel.Phase.Idle -> PasteStep(
                    url = state.url,
                    onUrlChange = viewModel::onUrlChanged,
                    onImport = viewModel::start,
                )
                is SpotifyImportViewModel.Phase.Error -> Column {
                    PasteStep(
                        url = state.url,
                        onUrlChange = viewModel::onUrlChanged,
                        onImport = viewModel::start,
                    )
                    Text(
                        phase.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Aurora.Error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is SpotifyImportViewModel.Phase.Fetching -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 24.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Vinyl.Gold,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Fetching playlist…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aurora.TextSecondary,
                    )
                }
                is SpotifyImportViewModel.Phase.Matching -> MatchingStep(
                    phase = phase,
                    onCancel = viewModel::cancel,
                )
                is SpotifyImportViewModel.Phase.Done -> DoneStep(
                    phase = phase,
                    onDone = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun PasteStep(
    url: String,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        singleLine = true,
        placeholder = {
            Text(
                "open.spotify.com/playlist/…",
                color = Aurora.TextSecondary,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                tint = Aurora.TextSecondary,
            )
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Aurora.TextPrimary,
            unfocusedTextColor = Aurora.TextPrimary,
            cursorColor = Vinyl.Gold,
            focusedBorderColor = Vinyl.Gold,
            unfocusedBorderColor = Aurora.Hairline,
            focusedContainerColor = Aurora.Surface,
            unfocusedContainerColor = Aurora.Surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Public playlists only · up to ${SpotifyPlaylistImporter.MAX_TRACKS} tracks",
        style = MaterialTheme.typography.bodySmall,
        color = Aurora.TextSecondary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Button(
            onClick = onImport,
            enabled = url.isNotBlank(),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Vinyl.Gold,
                contentColor = Aurora.Bg,
                disabledContainerColor = Vinyl.GoldDim.copy(alpha = 0.4f),
                disabledContentColor = Aurora.Bg,
            ),
        ) { Text("Import", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun MatchingStep(
    phase: SpotifyImportViewModel.Phase.Matching,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            "Matching ${phase.done} of ${phase.total}…",
            style = MaterialTheme.typography.bodyMedium,
            color = Aurora.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(contentColor = Aurora.TextSecondary),
        ) { Text("Cancel") }
    }
    LinearProgressIndicator(
        progress = {
            if (phase.total == 0) 0f else phase.done.toFloat() / phase.total
        },
        color = Vinyl.Gold,
        trackColor = Aurora.SurfaceLight,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
    ) {
        itemsIndexed(phase.rows, key = { i, _ -> i }) { _, row ->
            ImportRow(row)
        }
    }
}

/** One progress row — TrackRow conventions minus the click affordance. */
@Composable
private fun ImportRow(row: SpotifyImportViewModel.ImportRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Aurora.SurfaceLight),
        ) {
            val uri = ArtworkUrls.sanitize(row.artworkUri)
            if (uri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uri)
                        .build(),
                    contentDescription = "Artwork for ${row.track.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                VinylDisc(
                    artworkUri = null,
                    title = row.track.title,
                    modifier = Modifier.fillMaxSize(),
                    labelRatio = 0.42f,
                    artworkContentDescription = null,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Aurora.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (row.status) {
                    SpotifyImportViewModel.ImportRow.Status.NOT_FOUND -> "Not found"
                    SpotifyImportViewModel.ImportRow.Status.FAILED -> "Couldn't match"
                    else -> row.track.artist.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (row.status) {
                    SpotifyImportViewModel.ImportRow.Status.NOT_FOUND,
                    SpotifyImportViewModel.ImportRow.Status.FAILED,
                    -> Aurora.Error
                    else -> Aurora.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (row.status) {
            SpotifyImportViewModel.ImportRow.Status.PENDING ->
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Vinyl.GoldDim,
                )
            SpotifyImportViewModel.ImportRow.Status.MATCHED ->
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Matched",
                    tint = Vinyl.Gold,
                )
            else ->
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = "Not matched",
                    tint = Aurora.Error,
                )
        }
    }
}

@Composable
private fun DoneStep(
    phase: SpotifyImportViewModel.Phase.Done,
    onDone: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = Vinyl.Gold,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Imported ${phase.imported} tracks to “${phase.playlistName}”",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = Aurora.TextPrimary,
        )
        if (phase.missed > 0) {
            Text(
                "${phase.missed} couldn't be matched on YouTube",
                style = MaterialTheme.typography.bodySmall,
                color = Aurora.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Vinyl.Gold,
                contentColor = Aurora.Bg,
            ),
        ) { Text("Done", fontWeight = FontWeight.SemiBold) }
    }
}
