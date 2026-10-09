package com.pianostream.feature.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Timeline
import com.pianostream.core.designsystem.component.TrackRow
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Queue sheet over `player.currentTimeline` (M9): rows and actions are inert
 * while `isPlaylistReady` is false — a Room-restored queue rendered over an
 * empty timeline must accept no input.
 *
 * Look follows the Spotify queue screen inside the turntable's charcoal+gold
 * language: a "Queue" header with the track count and a trailing Clear action
 * (`onClearQueue`, wired to `PlayerViewModel.clearQueue` by the caller), a
 * "Now playing" section for the current row (raised gold-ringed pill, same
 * gradient/hairline recipe as DiscCard, plus an animated equalizer-bars
 * indicator), and an "Up next" section for every row after it — already-played
 * rows leave the sheet, same as the reference. Reorder commits straight into
 * `PlayerViewModel.move` (CC1 `moveMediaItem`, mirrored to `queue_items`) — no
 * queue logic lives here beyond what the view model already exposes.
 */
@Composable
fun QueueSheet(
    viewModel: PlayerViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onClearQueue: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = viewModel.player
    val timeline = player?.currentTimeline
    val count = timeline?.windowCount ?: 0
    val current = player?.currentMediaItemIndex ?: -1

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // `from`/`to` are always reorderable rows — section labels are never
        // drop targets — so map the drag back to timeline indices through the
        // row keys. Position math would be brittle now that labels sit
        // between rows.
        val tl = player?.currentTimeline ?: return@rememberReorderableLazyListState
        val window = Timeline.Window()
        fun indexOfKey(key: Any): Int {
            for (i in 0 until tl.windowCount) {
                if (tl.getWindow(i, window).uid == key) return i
            }
            return -1
        }
        val fromIndex = indexOfKey(from.key)
        val toIndex = indexOfKey(to.key)
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            viewModel.move(fromIndex, toIndex)
        }
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Queue",
                        color = Vinyl.Gold,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (count == 1) "1 track" else "$count tracks",
                        style = MaterialTheme.typography.labelMedium,
                        color = Aurora.TextSecondary,
                    )
                }
                TextButton(
                    onClick = onClearQueue,
                    enabled = state.isPlaylistReady && count > 0,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Vinyl.Gold,
                        disabledContentColor = Vinyl.GoldDim.copy(alpha = 0.5f),
                    ),
                ) {
                    Text("Clear")
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
        if (!state.isPlaylistReady || timeline == null || count == 0) {
            item { QueueEmptyState() }
        } else if (current in 0 until count) {
            item { QueueSectionLabel("Now playing") }
            queueItems(
                reorderableState = reorderableState,
                timeline = timeline,
                startIndex = current,
                count = 1,
                currentIndex = current,
                isPlaying = state.isPlaying,
                haptic = haptic,
                onPlay = viewModel::playAt,
                onRemove = viewModel::removeAt,
            )
            if (current + 1 < count) {
                item { QueueSectionLabel("Up next") }
                queueItems(
                    reorderableState = reorderableState,
                    timeline = timeline,
                    startIndex = current + 1,
                    count = count - current - 1,
                    currentIndex = current,
                    isPlaying = state.isPlaying,
                    haptic = haptic,
                    onPlay = viewModel::playAt,
                    onRemove = viewModel::removeAt,
                )
            }
        } else {
            item { QueueSectionLabel("Up next") }
            queueItems(
                reorderableState = reorderableState,
                timeline = timeline,
                startIndex = 0,
                count = count,
                currentIndex = current,
                isPlaying = state.isPlaying,
                haptic = haptic,
                onPlay = viewModel::playAt,
                onRemove = viewModel::removeAt,
            )
        }
    }
}

/** Queue rows for the consecutive timeline range [startIndex, startIndex+count). */
private fun LazyListScope.queueItems(
    reorderableState: ReorderableLazyListState,
    timeline: Timeline,
    startIndex: Int,
    count: Int,
    currentIndex: Int,
    isPlaying: Boolean,
    haptic: HapticFeedback,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    items(
        count = count,
        key = { k -> timeline.getWindow(startIndex + k, Timeline.Window()).uid },
    ) { k ->
        val i = startIndex + k
        val window = timeline.getWindow(i, Timeline.Window())
        val item = window.mediaItem
        val isCurrent = i == currentIndex
        ReorderableItem(reorderableState, key = window.uid) { isDragging ->
            QueueRow(
                title = item.mediaMetadata.title?.toString() ?: "Track ${i + 1}",
                subtitle = item.mediaMetadata.artist?.toString(),
                artworkUri = item.mediaMetadata.artworkUri?.toString(),
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                isDragging = isDragging,
                onClick = { onPlay(i) },
                onRemove = { onRemove(i) },
                handleModifier = Modifier.draggableHandle(
                    onDragStarted = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                ),
            )
        }
    }
}

/** Gold section label ("Now playing" / "Up next") separating queue groups. */
@Composable
private fun QueueSectionLabel(text: String) {
    Text(
        text,
        color = Vinyl.Gold,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 2.dp),
    )
}

/** Centred vinyl + copy shown when the timeline has nothing queued. */
@Composable
private fun QueueEmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VinylDisc(
            artworkUri = null,
            title = "",
            modifier = Modifier.size(128.dp),
            labelRatio = 0.38f,
            artworkContentDescription = null,
        )
        Text(
            "Queue is empty",
            style = MaterialTheme.typography.titleMedium,
            color = Aurora.TextPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Play something to fill it",
            style = MaterialTheme.typography.bodyMedium,
            color = Aurora.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Three animated bars marking the playing row; frozen mid-height when paused. */
@Composable
private fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val fractions = if (playing) {
        val transition = rememberInfiniteTransition(label = "eqBars")
        listOf(0, 1, 2).map { i ->
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 380 + i * 140,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "eqBar$i",
            ).value
        }
    } else {
        listOf(0.4f, 0.7f, 0.5f)
    }
    Canvas(modifier.size(width = 14.dp, height = 16.dp)) {
        val barWidth = size.width * 0.2f
        val gap = (size.width - barWidth * 3) / 2f
        fractions.forEachIndexed { i, fraction ->
            val barHeight = size.height * fraction
            drawRoundRect(
                color = Vinyl.Gold,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * One queue row. `TrackRow` supplies the thumb/text/trailing skeleton; this
 * wrapper adds the sheet chrome around it: raised gold-ringed pill on the
 * playing track, equalizer bars, delete affordance, and the reorder handle at
 * the right edge (convention from the Spotify queue reference).
 */
@Composable
private fun QueueRow(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    isCurrent: Boolean,
    isPlaying: Boolean,
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
                    if (isCurrent) {
                        EqualizerBars(
                            playing = isPlaying,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
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
