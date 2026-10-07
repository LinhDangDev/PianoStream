package com.pianostream.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Timeline
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.TrackRow
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Queue sheet over `player.currentTimeline` (M9): rows and actions are inert
 * while `isPlaylistReady` is false — a Room-restored queue rendered over an
 * empty timeline must accept no input.
 *
 * Look follows the YouTube Music "Up Next" sheet inside the turntable's
 * charcoal+gold language: a centred gold header, a raised neumorphic pill on
 * the playing row (same gradient/hairline recipe as DiscCard), and a right
 * edge drag handle. Reorder commits straight into `PlayerViewModel.move`
 * (CC1 `moveMediaItem`, mirrored to `queue_items`) — no queue logic lives
 * here beyond what the view model already exposes.
 */
@Composable
fun QueueSheet(
    viewModel: PlayerViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = viewModel.player

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // `from`/`to` are LazyColumn positions; slot 0 is the header, so the
        // timeline index is position - 1.
        viewModel.move(from.index - 1, to.index - 1)
    }
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Vinyl.GoldDim),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    "Up next",
                    color = Vinyl.Gold,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (state.isPlaylistReady) {
                    Text(
                        "${player?.currentTimeline?.windowCount ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Vinyl.GoldDim,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(Aurora.Hairline),
            )
        }
        if (!state.isPlaylistReady) {
            item {
                EmptyState(
                    title = "Queue empty",
                    body = "Play something to fill the queue.",
                )
            }
        } else if (player != null) {
            val timeline = player.currentTimeline
            val count = timeline.windowCount
            items(
                count = count,
                key = { i -> timeline.getWindow(i, Timeline.Window()).uid },
            ) { i ->
                val window = timeline.getWindow(i, Timeline.Window())
                val item = window.mediaItem
                ReorderableItem(reorderableState, key = window.uid) { isDragging ->
                    QueueRow(
                        title = item.mediaMetadata.title?.toString() ?: "Track ${i + 1}",
                        subtitle = item.mediaMetadata.artist?.toString(),
                        artworkUri = item.mediaMetadata.artworkUri?.toString(),
                        isCurrent = i == player.currentMediaItemIndex,
                        isDragging = isDragging,
                        onClick = { viewModel.playAt(i) },
                        onRemove = { viewModel.removeAt(i) },
                        handleModifier = Modifier.draggableHandle(
                            onDragStarted = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One queue row. `TrackRow` supplies the thumb/text/trailing skeleton; this
 * wrapper adds the sheet chrome around it: raised gold-ringed pill on the
 * playing track, delete affordance, and the reorder handle at the right edge
 * (convention from the Up Next reference).
 */
@Composable
private fun QueueRow(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    isCurrent: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    handleModifier: Modifier,
) {
    val raised = isCurrent || isDragging
    val elevation by animateDpAsState(if (isDragging) 10.dp else if (isCurrent) 6.dp else 0.dp, label = "rowLift")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .shadow(elevation, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (raised) {
                    Modifier
                        .background(
                            // Same raised recipe as DiscCard.
                            Brush.linearGradient(
                                listOf(Color(0xFF2A2D32), Color(0xFF1B1C1F)),
                            ),
                        )
                        .border(
                            0.5.dp,
                            if (isDragging) Vinyl.Gold.copy(alpha = 0.45f) else Aurora.Hairline,
                            RoundedCornerShape(16.dp),
                        )
                } else {
                    Modifier
                },
            )
            .drawBehind {
                // Gold ring on the playing thumb. The circle centres on
                // TrackRow's 48dp artwork (16dp row padding + 24dp radius).
                if (isCurrent) {
                    drawCircle(
                        Vinyl.Gold.copy(alpha = if (isDragging) 0.9f else 0.65f),
                        radius = 25.5.dp.toPx(),
                        center = Offset(40.dp.toPx(), center.y),
                        style = Stroke(1.4.dp.toPx()),
                    )
                }
            },
    ) {
        TrackRow(
            title = title,
            subtitle = subtitle,
            artworkUri = artworkUri,
            durationMs = null,
            isCurrent = isCurrent,
            artworkShape = CircleShape,
            onClick = onClick,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove from queue",
                            tint = Vinyl.GoldDim,
                        )
                    }
                    IconButton(
                        onClick = {},
                        modifier = Modifier
                            .size(40.dp)
                            .then(handleModifier),
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = Vinyl.GoldDim.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
    }
}
