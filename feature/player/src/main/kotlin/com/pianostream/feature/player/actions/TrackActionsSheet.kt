package com.pianostream.feature.player.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianostream.core.data.downloads.DownloadState
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora

/**
 * Track context menu — the reusable "…" sheet from Spotify's track menu,
 * restyled to the turntable's charcoal+champagne language (same container
 * and hairline recipe as [com.pianostream.feature.player.QueueSheet]).
 *
 * The sheet is display-only: it knows a title/subtitle/artwork and which
 * actions are enabled, and reports taps through [onAction]. Mount it from
 * anywhere a track row lives — the expanded player's ⋯ button, a search
 * row long-press, a library row — and let the caller own behaviour.
 *
 * Rows render in [TrackAction] declaration order filtered to [enabled].
 * Every action fires [onAction] then dismisses; [TrackAction.ToggleFavorite]
 * flips its heart and label off [isFavorite]. A "Cancel" row always sits at
 * the bottom and only calls [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    title: String,
    subtitle: String,
    artworkUri: String?,
    isFavorite: Boolean,
    enabled: Set<TrackAction>,
    onAction: (TrackAction) -> Unit,
    onDismiss: () -> Unit,
    /** Download row state (null = the row reads "Download"); enabled only
     *  by hosts wired to the download manager. */
    downloadState: DownloadState? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // Header: the track the menu acts on (vinyl thumb like a queue row).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                VinylDisc(
                    artworkUri = artworkUri,
                    title = title,
                    modifier = Modifier.size(48.dp),
                    labelRatio = 0.42f,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Aurora.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            color = Aurora.TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(Aurora.Hairline),
            ) {}
            actionRows(enabled).forEach { action ->
                ActionRow(
                    action = action,
                    isFavorite = isFavorite,
                    downloadState = downloadState,
                    onClick = {
                        onAction(action)
                        onDismiss()
                    },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(Aurora.Hairline),
            ) {}
            ActionRow(
                icon = Icons.Filled.Close,
                label = "Cancel",
                iconTint = Vinyl.GoldDim,
                onClick = onDismiss,
            )
        }
    }
}

/** One sheet row: 22dp icon + label, ≥56dp touch target, charcoal chrome. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(label, color = Aurora.TextPrimary, fontSize = 16.sp)
    }
}

@Composable
private fun ActionRow(
    action: TrackAction,
    isFavorite: Boolean,
    downloadState: DownloadState?,
    onClick: () -> Unit,
) {
    ActionRow(
        icon = action.icon(isFavorite, downloadState),
        label = action.label(isFavorite, downloadState),
        iconTint = if (
            (action == TrackAction.ToggleFavorite && isFavorite) ||
            (action == TrackAction.Download && downloadState == DownloadState.DONE)
        ) {
            Vinyl.Gold
        } else {
            Vinyl.GoldDim
        },
        onClick = onClick,
    )
}
