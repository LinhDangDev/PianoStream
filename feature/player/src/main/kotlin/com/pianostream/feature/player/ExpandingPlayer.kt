package com.pianostream.feature.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.designsystem.art.ArtworkUrls
import com.pianostream.core.designsystem.component.PlaybackErrorState
import com.pianostream.core.designsystem.component.formatDuration
import com.pianostream.core.designsystem.theme.Aurora
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Aurora expanding player (ported from Melody's ExpandingPlayer): a docked
 * 52dp pill parked above the nav bar that morphs into the full player —
 * corner radius, margins, surface and a flying artwork all driven by one
 * `offset` animatable. Swipe down or tap the chevron to dock it.
 *
 * The queue opens as a [ModalBottomSheet] hosting [QueueSheet]; remove/reorder
 * semantics ("Remove from queue") live there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandingPlayer(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.connect() }

    val item = state.currentItem
    if (item == null) {
        // Nothing queued — no dock. Never leave the sheet stuck "expanded"
        // with no surface behind it.
        LaunchedEffect(Unit) { if (expanded) onExpandedChange(false) }
        return
    }
    val title = item.mediaMetadata.title?.toString().orEmpty()
    val artist = item.mediaMetadata.artist?.toString().orEmpty()
    val artwork = ArtworkUrls.sanitize(item.mediaMetadata.artworkUri?.toString())

    var queueOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val artworkRotation = remember { Animatable(0f) }

    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            while (isActive) {
                artworkRotation.animateTo(
                    artworkRotation.value + 360f,
                    animationSpec = tween(
                        durationMillis = 12_000,
                        easing = LinearEasing,
                    ),
                )
            }
        }
    }

    BackHandler(enabled = expanded) { onExpandedChange(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerHeightPx = constraints.maxHeight.toFloat()
        val collapsedHeightPx = with(density) { 52.dp.toPx() }
        val bottomMarginPx = with(density) { 8.dp.toPx() }
        val swipeLimit = (containerHeightPx - collapsedHeightPx - bottomMarginPx)
            .coerceAtLeast(0f)

        val offset = remember { Animatable(0f) }
        // Re-park when the container resizes while docked (nav bar appearing /
        // disappearing mid-collapse).
        LaunchedEffect(swipeLimit) {
            if (!expanded && swipeLimit > 0f) offset.snapTo(swipeLimit)
        }
        LaunchedEffect(expanded) {
            if (swipeLimit <= 0f) return@LaunchedEffect
            offset.animateTo(
                if (expanded) 0f else swipeLimit,
                spring(stiffness = Spring.StiffnessMediumLow),
            )
            if (!expanded) offset.snapTo(swipeLimit)
        }

        val fraction = if (swipeLimit > 0f) {
            (1f - offset.value / swipeLimit).coerceIn(0f, 1f)
        } else 0f
        val expandedAlpha = ((fraction - 0.3f) / 0.7f).coerceIn(0f, 1f)
        val collapsedAlpha = (1f - fraction / 0.7f).coerceIn(0f, 1f)

        if (swipeLimit <= 0f) return@BoxWithConstraints

        val cornerDp = lerp(24f, 0f, fraction).dp
        val bg = lerp(Aurora.GlassStrong, Aurora.BgDeep, fraction)

        // Morphing-artwork geometry — computed in BoxWithConstraints scope
        // (reading `constraints` inside the player Box's content is a
        // DSL-scope violation).
        val containerWidthPx = constraints.maxWidth.toFloat()
        val miniSize = 38.dp
        val discSizeDp = with(density) { containerWidthPx.toDp() } * 0.78f
        val miniX = with(density) { 8.dp.toPx() }
        val miniY = with(density) { 7.dp.toPx() }
        val fullX = (containerWidthPx - with(density) { discSizeDp.toPx() }) / 2f
        val fullY = with(density) { 96.dp.toPx() }
        val shrink = miniSize.value / discSizeDp.value

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .padding(horizontal = lerp(10f, 0f, fraction).dp)
                .padding(bottom = lerp(8f, 0f, fraction).dp)
                // Don't flash the expanded surface on the first composed
                // frame (offset starts at 0 until the park snap lands).
                .graphicsLayer {
                    alpha = if (!expanded && offset.value <= 0.5f) 0f else 1f
                    clip = true
                    shape = RoundedCornerShape(cornerDp)
                    shadowElevation = with(density) {
                        lerp(10f, 0f, fraction).dp.toPx()
                    }
                }
                .drawBehind {
                    drawRoundRect(
                        color = bg,
                        cornerRadius = CornerRadius(cornerDp.toPx()),
                    )
                    // Hairline only while docked — the expanded surface is
                    // full-bleed.
                    if (fraction < 0.9f) {
                        drawRoundRect(
                            color = Aurora.Hairline.copy(
                                alpha = Aurora.Hairline.alpha * (1f - fraction),
                            ),
                            cornerRadius = CornerRadius(cornerDp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = with(density) { 0.5.dp.toPx() },
                            ),
                        )
                    }
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offset.snapTo((offset.value + delta).coerceIn(0f, swipeLimit))
                        }
                    },
                    onDragStopped = { velocity ->
                        val target = if (expanded) {
                            if (velocity > 500 || offset.value > 40f) swipeLimit else 0f
                        } else {
                            if (velocity < -500 || offset.value < swipeLimit - 40f) 0f else swipeLimit
                        }
                        onExpandedChange(target == 0f)
                        scope.launch {
                            offset.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                            if (target != 0f) offset.snapTo(swipeLimit)
                        }
                    },
                )
                .semantics { contentDescription = "Open player: $title" }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onExpandedChange(!expanded)
                },
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides Aurora.TextPrimary,
            ) {
                // Blurred artwork backdrop (API 31+) — declared first so it
                // sits below the morphing artwork and the controls.
                if (Build.VERSION.SDK_INT >= 31 && expandedAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = expandedAlpha },
                    ) {
                        if (artwork != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(artwork)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(70.dp)
                                    .graphicsLayer { scaleX = 1.5f; scaleY = 1.5f },
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                        )
                    }
                }

                // ---- collapsed dock row (Spotify Connect mini: circle thumb,
                // accent title + artist, small prev/next, round play button) ----
                if (fraction < 0.7f) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .graphicsLayer { alpha = collapsedAlpha },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 8.dp, end = 8.dp),
                        ) {
                            ArtworkBox(artwork, title, Modifier.size(38.dp), 0, circle = true)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Aurora.Accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (artist.isNotBlank()) {
                                    Text(
                                        artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Aurora.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = viewModel::previous, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Filled.SkipPrevious,
                                    contentDescription = "Previous track",
                                    tint = Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(onClick = viewModel::next, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Filled.SkipNext,
                                    contentDescription = "Next track",
                                    tint = Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = viewModel::playPause,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(38.dp)
                                    .background(Color.White, CircleShape),
                            ) {
                                Icon(
                                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        // Progress hairline at the dock's lower edge.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Aurora.Accent.copy(alpha = 0.2f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(
                                        if (state.durationMs > 0) {
                                            (state.positionMs.toFloat() / state.durationMs)
                                                .coerceIn(0f, 1f)
                                        } else 0f,
                                    )
                                    .background(Aurora.Accent),
                            )
                        }
                    }
                }

                // ---- morphing artwork (mini thumb -> large artwork) ----
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                lerp(miniX, fullX, fraction).roundToInt(),
                                lerp(miniY, fullY, fraction).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            val s = lerp(shrink, 1f, fraction)
                            scaleX = s
                            scaleY = s
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            clip = true
                            shape = CircleShape // vinyl-circle artwork
                            shadowElevation = with(density) { lerp(0f, 24f, fraction).dp.toPx() }
                            alpha = fraction
                        }
                        .size(discSizeDp),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = artworkRotation.value },
                    ) {
                        ArtworkBox(artwork, title, Modifier.fillMaxSize(), 0, circle = true)
                    }
                }

                // ---- expanded content ----
                if (fraction > 0.05f) {
                    val statusPad = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .graphicsLayer {
                                alpha = expandedAlpha
                                translationY = if (fraction < 0.2f) 10000f else 0f
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Grabber + collapse chevron.
                        Box(Modifier.fillMaxWidth().height(40.dp)) {
                            IconButton(
                                onClick = { onExpandedChange(false) },
                                modifier = Modifier.align(Alignment.CenterStart),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Collapse player",
                                    tint = Aurora.TextSecondary,
                                )
                            }
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp, 5.dp)
                                    .background(Color.White.copy(alpha = 0.3f), CircleShape),
                            )
                        }

                        // Artwork region the morphing circle lands in, with the
                        // seek bar + times overlaid on its bottom edge (per
                        // the reference: bar sits ON the circle, times flank it).
                        val artMarginDp =
                            (with(density) { containerWidthPx.toDp() } - discSizeDp) / 2f
                        // Region bottom must land on the circle's bottom edge:
                        // subtract the status-bar + grabber heights above it.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    with(density) { fullY.toDp() } + discSizeDp
                                        - statusPad - 40.dp,
                                ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = artMarginDp - 8.dp),
                            ) {
                                Text(
                                    formatDuration(state.positionMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Aurora.TextPrimary.copy(alpha = 0.9f),
                                )
                                ThinTrackBar(
                                    fraction = if (state.durationMs > 0) {
                                        (state.positionMs.toFloat() / state.durationMs)
                                            .coerceIn(0f, 1f)
                                    } else 0f,
                                    onChange = {
                                        if (state.durationMs > 0) {
                                            viewModel.seekTo((it * state.durationMs).toLong())
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp),
                                )
                                Text(
                                    formatDuration(state.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Aurora.TextPrimary.copy(alpha = 0.9f),
                                )
                            }
                        }

                        state.error?.let {
                            PlaybackErrorState(message = it, onRetry = viewModel::retryCurrent)
                        }

                        Spacer(Modifier.weight(1f))

                        // Frosted control card: accent title + artist | prev/next
                        // | white round play button (per reference).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Aurora.Glass)
                                .border(0.5.dp, Aurora.Hairline, RoundedCornerShape(22.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Aurora.Accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    artist.ifBlank { "Unknown artist" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Aurora.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = viewModel::previous, modifier = Modifier.size(38.dp)) {
                                Icon(
                                    Icons.Filled.SkipPrevious,
                                    contentDescription = "Previous track",
                                    tint = Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(onClick = viewModel::next, modifier = Modifier.size(38.dp)) {
                                Icon(
                                    Icons.Filled.SkipNext,
                                    contentDescription = "Next track",
                                    tint = Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(
                                onClick = viewModel::playPause,
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(Color.White, CircleShape),
                            ) {
                                Icon(
                                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Bottom row: shuffle / queue / repeat.
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 40.dp),
                        ) {
                            IconButton(onClick = viewModel::toggleShuffle) {
                                Icon(
                                    Icons.Filled.Shuffle,
                                    contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                                    tint = if (state.shuffle) {
                                        Aurora.Accent
                                    } else Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(onClick = { queueOpen = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "Open play queue",
                                    tint = Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(onClick = viewModel::cycleRepeat) {
                                Icon(
                                    when (state.repeatMode) {
                                        androidx.media3.common.Player.REPEAT_MODE_ONE ->
                                            Icons.Filled.RepeatOne
                                        else -> Icons.Filled.Repeat
                                    },
                                    contentDescription = "Repeat mode",
                                    tint = if (state.repeatMode !=
                                        androidx.media3.common.Player.REPEAT_MODE_OFF
                                    ) Aurora.Accent else Aurora.TextPrimary.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (queueOpen) {
        ModalBottomSheet(
            onDismissRequest = { queueOpen = false },
            containerColor = Aurora.Surface,
        ) {
            QueueSheet(modifier = Modifier.fillMaxHeight(0.75f))
        }
    }
}

/** Artwork with a glass placeholder (music note) when the URI sanitizes to null. */
@Composable
private fun ArtworkBox(
    artworkUri: String?,
    title: String,
    modifier: Modifier = Modifier,
    corner: Int,
    circle: Boolean = false,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(corner.dp)
    Box(modifier.clip(shape).background(Aurora.SurfaceLight)) {
        if (artworkUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUri)
                    .build(),
                contentDescription = "Artwork for $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Aurora.TextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.5f),
            )
        }
    }
}

/** Apple's thin progress slider (ported from Melody, ported from Cue, MIT):
 *  3.5dp white track + small knob, tap or drag to seek. */
@Composable
private fun ThinTrackBar(
    fraction: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.height(28.dp)) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(3.5.dp)
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                },
        ) {
            drawRoundRect(
                Color.White.copy(alpha = 0.22f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height),
                cornerRadius = CornerRadius(size.height / 2),
            )
            drawRoundRect(
                Color.White,
                size = androidx.compose.ui.geometry.Size(
                    size.width * fraction.coerceIn(0f, 1f),
                    size.height,
                ),
                cornerRadius = CornerRadius(size.height / 2),
            )
            drawCircle(
                Color.White,
                radius = 4.5.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(
                    size.width * fraction.coerceIn(0f, 1f),
                    size.height / 2,
                ),
            )
        }
    }
}
