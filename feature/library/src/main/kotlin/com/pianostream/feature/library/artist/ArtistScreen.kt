package com.pianostream.feature.library.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.designsystem.component.LoadingShimmer
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.SingleBadge
import com.pianostream.core.designsystem.component.TrackRow
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora
import com.pianostream.feature.library.isSingle

/**
 * Artist detail — Spotify's artist page translated to the local library:
 * circle-initial avatar header, gold "Play" + outlined "Shuffle" actions,
 * then "Discs" and "Tracks" sections. Rows reuse `TrackRow` (and the
 * "Open disc" semantics wrapper) so the page reads like Library's tabs.
 *
 * Navigation is the integrator's job (`artist/{artistName}` route); the
 * screen compiles standalone — [artistName] arrives as a plain parameter.
 */
@Composable
fun ArtistScreen(
    artistName: String,
    onBack: () -> Unit,
    onPlayTracks: (List<QueueItem>, Int) -> Unit,
    onOpenDisc: (Disc) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(artistName) { viewModel.load(artistName) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Aurora.TextPrimary,
                )
            }
        }

        if (state.loaded && state.discs.isEmpty() && state.tracks.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VinylDisc(
                    artworkUri = null,
                    title = artistName,
                    modifier = Modifier.size(140.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Nothing by this artist yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = Aurora.TextPrimary,
                )
                Text(
                    "Tracks and discs tagged \"$artistName\" appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aurora.TextSecondary,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "header") { ArtistHeader(artistName, state) }
                item(key = "actions") {
                    ArtistActions(
                        enabled = state.queue.isNotEmpty(),
                        onPlay = {
                            val (items, start) = viewModel.playAll()
                            onPlayTracks(items, start)
                        },
                        onShuffle = {
                            val (items, start) = viewModel.shuffleAll()
                            onPlayTracks(items, start)
                        },
                    )
                }
                if (!state.loaded) {
                    item(key = "loading") { LoadingShimmer(rows = 4) }
                }
                if (state.discs.isNotEmpty()) {
                    item(key = "discs-header") { SectionHeader("Discs") }
                    items(state.discs, key = { it.id }) { disc ->
                        Box(
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "Open disc: ${disc.title}"
                            },
                        ) {
                            TrackRow(
                                title = disc.title,
                                subtitle = disc.performer ?: disc.composer,
                                artworkUri = disc.artworkUri,
                                durationMs = null,
                                onClick = { onOpenDisc(disc) },
                                trailing = if (disc.isSingle()) {
                                    { SingleBadge() }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                if (state.trackRows.isNotEmpty()) {
                    item(key = "tracks-header") { SectionHeader("Tracks") }
                    itemsIndexed(state.trackRows, key = { _, r -> r.track.id }) { index, row ->
                        TrackRow(
                            title = row.track.title,
                            // A single's disc is named after its one track —
                            // repeating it as the subtitle adds nothing.
                            subtitle = row.discTitle
                                .takeIf { it != row.track.title },
                            artworkUri = row.discArtworkUri,
                            durationMs = row.track.endMs?.let { it - row.track.startMs },
                            onClick = {
                                val (items, start) = viewModel.queueFrom(index)
                                onPlayTracks(items, start)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Avatar + name + counts line (Spotify's artist header, local flavor). */
@Composable
private fun ArtistHeader(artistName: String, state: ArtistViewModel.ArtistUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Vinyl.Face)
                .border(2.dp, Vinyl.Gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                artistInitial(artistName),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Vinyl.Gold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                artistName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Aurora.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                countLabel(state.discs.size, "disc") +
                    " · " + countLabel(state.tracks.size, "track"),
                style = MaterialTheme.typography.bodyMedium,
                color = Aurora.TextSecondary,
            )
        }
    }
}

/** Filled gold "Play" + outlined "Shuffle" — Spotify's primary actions. */
@Composable
private fun ArtistActions(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onPlay,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Vinyl.Gold,
                contentColor = Aurora.onAccent,
                disabledContainerColor = Vinyl.GoldDim.copy(alpha = 0.4f),
                disabledContentColor = Aurora.onAccent.copy(alpha = 0.5f),
            ),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Play", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onShuffle,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Vinyl.Gold,
                disabledContentColor = Vinyl.GoldDim.copy(alpha = 0.5f),
            ),
            border = BorderStroke(1.dp, Vinyl.Gold),
        ) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Shuffle", fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun artistInitial(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** "1 disc" / "3 discs" — singular drops the s. */
private fun countLabel(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"
