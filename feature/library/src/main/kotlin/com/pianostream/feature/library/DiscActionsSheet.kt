package com.pianostream.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora

/** Actions a disc's long-press sheet can dispatch (Library spec). */
enum class DiscAction {
    Play,
    Shuffle,
    AddToQueue,
    GoToArtist,
    AddToPlaylist,
}

private data class DiscActionRow(
    val action: DiscAction,
    val label: String,
    val icon: ImageVector,
)

// Spotify album long-press sheet order: play actions first, library
// actions next, playlist add last; Cancel sits under its own divider.
private val discActionRows = listOf(
    DiscActionRow(DiscAction.Play, "Play", Icons.Filled.PlayArrow),
    DiscActionRow(DiscAction.Shuffle, "Shuffle play", Icons.Filled.Shuffle),
    DiscActionRow(DiscAction.AddToQueue, "Add all to queue", Icons.Filled.AddToQueue),
    DiscActionRow(DiscAction.GoToArtist, "Go to artist", Icons.Filled.Person),
    DiscActionRow(
        DiscAction.AddToPlaylist,
        "Add all to playlist",
        Icons.AutoMirrored.Outlined.PlaylistAdd,
    ),
)

/**
 * Long-press context sheet for a disc/single — a pure UI surface: every
 * action is reported through [onAction] and the host owns playback,
 * navigation, and playlist wiring.
 *
 * [subtitle] is caller-composed ("artist · N tracks"). Actions missing from
 * [enabled] render dimmed and never fire [onAction].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscActionsSheet(
    title: String,
    subtitle: String,
    enabled: Set<DiscAction>,
    onAction: (DiscAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
    ) {
        DiscActionsSheetContent(
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

/** Sheet body, split out so previews/tests can render it without a window. */
@Composable
private fun DiscActionsSheetContent(
    title: String,
    subtitle: String,
    enabled: Set<DiscAction>,
    onAction: (DiscAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
    ) {
        // Header — vinyl thumb + title + "artist · N tracks" credit line,
        // mirroring DiscHeader's type hierarchy.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            VinylDisc(
                artworkUri = null,
                title = title,
                modifier = Modifier.size(64.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Aurora.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Aurora.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = Aurora.Hairline)
        discActionRows.forEach { row ->
            DiscActionSheetRow(
                label = row.label,
                icon = row.icon,
                enabled = row.action in enabled,
                onClick = { onAction(row.action) },
            )
        }
        HorizontalDivider(color = Aurora.Hairline)
        DiscActionSheetRow(
            label = "Cancel",
            icon = Icons.Filled.Close,
            enabled = true,
            muted = true,
            onClick = onDismiss,
        )
    }
}

/**
 * One 48dp sheet row — GoldDim icon + label, same rhythm as the player's
 * actions sheet (22dp icon, 16sp text). [muted] renders the Cancel row in
 * the secondary color; a disabled row dims out and drops its click.
 */
@Composable
private fun DiscActionSheetRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (muted) Aurora.TextSecondary else Vinyl.GoldDim,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            color = if (muted) Aurora.TextSecondary else Aurora.TextPrimary,
            fontSize = 16.sp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF2B2E33)
@Composable
private fun DiscActionsSheetPreview() {
    DiscActionsSheetContent(
        title = "Kind of Blue",
        subtitle = "Miles Davis · 5 tracks",
        enabled = setOf(DiscAction.Play, DiscAction.AddToQueue, DiscAction.AddToPlaylist),
        onAction = {},
        onDismiss = {},
    )
}
