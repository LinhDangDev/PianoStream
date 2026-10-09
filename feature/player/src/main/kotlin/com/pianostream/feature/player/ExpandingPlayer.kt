package com.pianostream.feature.player

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.designsystem.art.ArtworkUrls
import com.pianostream.core.designsystem.component.PlaybackErrorState
import com.pianostream.core.designsystem.component.Vinyl
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
    actions: PlayerActions = PlayerActions(),
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
    var actionsOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val artworkRotation = remember { Animatable(0f) }

    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            while (isActive) {
                artworkRotation.animateTo(
                    artworkRotation.value + 360f,
                    animationSpec = tween(
                        durationMillis = Vinyl.RPM_MS,
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
        val bg = lerp(Aurora.GlassStrong, Vinyl.BgBottom, fraction)

        // Morphing-label geometry — computed in BoxWithConstraints scope
        // (reading `constraints` inside the player Box's content is a
        // DSL-scope violation). The dock's mini-record label flies to the
        // centre of the turntable record.
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerWidthDp = with(density) { containerWidthPx.toDp() }
        val statusPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val tableSize = containerWidthDp - TableMargin * 2
        val labelSizeDp = tableSize * (Vinyl.RECORD_RATIO * Vinyl.LABEL_RATIO)
        val miniSize = MiniDisc * Vinyl.MINI_LABEL_RATIO
        val miniX = with(density) { (8.dp + (MiniDisc - miniSize) / 2f).toPx() }
        val miniY = with(density) { (7.dp + (MiniDisc - miniSize) / 2f).toPx() }
        val fullX = with(density) { ((containerWidthDp - labelSizeDp) / 2f).toPx() }
        val fullY = with(density) {
            (statusPad + TopBarHeight + TableTopGap + (tableSize - labelSizeDp) / 2f).toPx()
        }
        val shrink = miniSize.value / labelSizeDp.value

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
                // Charcoal backdrop of the turntable view.
                if (expandedAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = expandedAlpha }
                            .background(Brush.verticalGradient(listOf(Vinyl.BgTop, Vinyl.BgBottom))),
                    )
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
                            MiniVinyl(artwork, title, { artworkRotation.value }, Modifier.size(MiniDisc))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Vinyl.Gold,
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
                                .background(Vinyl.Gold.copy(alpha = 0.2f)),
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
                                    .background(Vinyl.Gold),
                            )
                        }
                    }
                }

                // ---- expanded turntable view ----
                if (fraction > 0.05f) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(TopBarHeight)
                                .padding(horizontal = TableMargin),
                        ) {
                            NeuButton(
                                icon = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Collapse player",
                                onClick = { onExpandedChange(false) },
                                size = 40.dp,
                                iconSize = 22.dp,
                            )
                            // YouTube items get YTM's Song|Video toggle in
                            // the header slot; everything else keeps the
                            // "Playing now" label.
                            if (state.videoCapable) {
                                Box(
                                    Modifier.weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SongVideoToggle(
                                        videoMode = state.videoMode,
                                        onChange = viewModel::setVideoMode,
                                    )
                                }
                            } else {
                                Text(
                                    "Playing now",
                                    color = Vinyl.Gold,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Spotify's ⋯ opens a track-actions sheet.
                            NeuButton(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = "Track actions",
                                onClick = { actionsOpen = true },
                                size = 40.dp,
                                iconSize = 22.dp,
                                tint = Vinyl.GoldDim,
                            )
                        }

                        Spacer(Modifier.height(TableTopGap))
                        if (state.videoMode) {
                            Box(Modifier.size(tableSize)) {
                                VideoPanel(
                                    player = viewModel.player,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                VideoQualityChip(
                                    currentItag = state.videoQuality,
                                    onSelect = viewModel::setVideoQuality,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(14.dp),
                                )
                            }
                        } else {
                            Turntable(
                                rotation = { artworkRotation.value },
                                playing = state.isPlaying,
                                modifier = Modifier.size(tableSize),
                            )
                        }

                        // Spotify-style: title/artist left-aligned under the
                        // platter, save-to-collection heart on the right edge.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = TableMargin)
                                .offset(y = (-26).dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 44.dp, end = 56.dp),
                            ) {
                                Text(
                                    title,
                                    color = Vinyl.Gold,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    artist.ifBlank { "Unknown artist" },
                                    color = Vinyl.GoldDim,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            NeuButton(
                                icon = if (state.isFavorite) {
                                    Icons.Filled.Favorite
                                } else Icons.Filled.FavoriteBorder,
                                contentDescription = if (state.isFavorite) {
                                    "Remove from collection"
                                } else "Save to collection",
                                onClick = viewModel::toggleFavorite,
                                size = 44.dp,
                                iconSize = 22.dp,
                                tint = if (state.isFavorite) Vinyl.Gold else Vinyl.GoldDim,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(top = 44.dp),
                            )
                        }

                        state.error?.let {
                            PlaybackErrorState(message = it, onRetry = viewModel::retryCurrent)
                        }

                        Spacer(Modifier.weight(1f))

                        Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                            GoldSlider(
                                fraction = if (state.durationMs > 0) {
                                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                                } else 0f,
                                onChange = {
                                    if (state.durationMs > 0) {
                                        viewModel.seekTo((it * state.durationMs).toLong())
                                    }
                                },
                            )
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            ) {
                                Text(formatDuration(state.positionMs), color = Vinyl.GoldDim, fontSize = 12.sp)
                                Text(formatDuration(state.durationMs), color = Vinyl.GoldDim, fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Spotify-style transport: one five-button row —
                        // shuffle | previous | play | next | repeat.
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        ) {
                            NeuButton(
                                icon = Icons.Filled.Shuffle,
                                contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                                onClick = viewModel::toggleShuffle,
                                size = 46.dp,
                                iconSize = 20.dp,
                                tint = if (state.shuffle) Vinyl.Gold else Vinyl.GoldDim,
                            )
                            NeuButton(
                                icon = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous track",
                                onClick = viewModel::previous,
                                size = 58.dp,
                                iconSize = 26.dp,
                            )
                            NeuButton(
                                icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                onClick = viewModel::playPause,
                                size = 80.dp,
                                iconSize = 34.dp,
                                tint = Vinyl.Gold,
                                ring = true,
                            )
                            NeuButton(
                                icon = Icons.Filled.SkipNext,
                                contentDescription = "Next track",
                                onClick = viewModel::next,
                                size = 58.dp,
                                iconSize = 26.dp,
                            )
                            val repeatOn = state.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF
                            NeuButton(
                                // Loop glyph (circular arrows) reads
                                // clearly apart from Shuffle's X-cross.
                                icon = if (state.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) {
                                    Icons.Filled.RepeatOne
                                } else Icons.Filled.Loop,
                                contentDescription = "Repeat mode",
                                onClick = viewModel::cycleRepeat,
                                size = 46.dp,
                                iconSize = 20.dp,
                                tint = if (repeatOn) Vinyl.Gold else Vinyl.GoldDim,
                            )
                        }

                        // Accessory strip below transport (Spotify/Apple
                        // Music's near-bottom row): speaker + thin volume
                        // slider on the left, queue pinned to the right edge.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, start = 24.dp, end = 24.dp),
                        ) {
                            VolumeRow(Modifier.weight(1f))
                            Spacer(Modifier.width(14.dp))
                            NeuButton(
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Open play queue",
                                onClick = { queueOpen = true },
                                size = 40.dp,
                                iconSize = 20.dp,
                                tint = Vinyl.GoldDim,
                            )
                        }

                        Spacer(Modifier.height(18.dp))
                    }
                }

                // ---- morphing label (mini record label -> turntable label) ----
                // Declared last so it sits on top of the record it lands on.
                // No record exists in video mode — no flying label either.
                if (!state.videoMode) {
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
                                alpha = fraction
                            }
                            .size(labelSizeDp),
                    ) {
                        // Spin about the label's own centre (the outer layer
                        // scales from its top-left corner).
                        VinylLabel(
                            artwork,
                            title,
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = artworkRotation.value },
                        )
                    }
                }
            }
        }
    }

    if (queueOpen) {
        ModalBottomSheet(
            onDismissRequest = { queueOpen = false },
            containerColor = Vinyl.BgTop,
        ) {
            QueueSheet(modifier = Modifier.fillMaxHeight(0.75f))
        }
    }

    if (actionsOpen) {
        ModalBottomSheet(
            onDismissRequest = { actionsOpen = false },
            containerColor = Vinyl.BgTop,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                SheetActionRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    tint = Vinyl.GoldDim,
                    label = "Open play queue",
                    onClick = {
                        actionsOpen = false
                        queueOpen = true
                    },
                )
                SheetActionRow(
                    icon = if (state.isFavorite) {
                        Icons.Filled.Favorite
                    } else Icons.Filled.FavoriteBorder,
                    tint = if (state.isFavorite) Vinyl.Gold else Vinyl.GoldDim,
                    label = if (state.isFavorite) {
                        "Remove from collection"
                    } else "Save to collection",
                    onClick = viewModel::toggleFavorite,
                )
                // Spotify "..." sheet entries — each row only exists when the
                // host wired its callback through PlayerActions.
                actions.onAddToPlaylist?.let { action ->
                    SheetActionRow(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        tint = Vinyl.GoldDim,
                        label = "Add to playlist",
                        onClick = { actionsOpen = false; action() },
                    )
                }
                actions.onAddToQueue?.let { action ->
                    SheetActionRow(
                        icon = Icons.Filled.AddToQueue,
                        tint = Vinyl.GoldDim,
                        label = "Add to queue",
                        onClick = { actionsOpen = false; action() },
                    )
                }
                actions.onGoToAlbum?.let { action ->
                    SheetActionRow(
                        icon = Icons.Filled.Album,
                        tint = Vinyl.GoldDim,
                        label = "Go to album",
                        onClick = { actionsOpen = false; action() },
                    )
                }
                actions.onGoToArtist?.let { action ->
                    SheetActionRow(
                        icon = Icons.Filled.Person,
                        tint = Vinyl.GoldDim,
                        label = "Go to artist",
                        onClick = { actionsOpen = false; action() },
                    )
                }
                actions.onShare?.let { action ->
                    SheetActionRow(
                        icon = Icons.Filled.Share,
                        tint = Vinyl.GoldDim,
                        label = "Share",
                        onClick = { actionsOpen = false; action() },
                    )
                }
                actions.onSleepTimer?.let { action ->
                    SheetActionRow(
                        icon = Icons.Filled.Bedtime,
                        tint = Vinyl.GoldDim,
                        label = "Sleep timer",
                        onClick = { actionsOpen = false; action() },
                    )
                }
            }
        }
    }
}

/** One row of the "..." actions sheet: icon + label, full-width tap target. */
@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(label, color = Aurora.TextPrimary, fontSize = 16.sp)
    }
}

private val TableMargin = 20.dp
private val MiniDisc = 38.dp
private val TopBarHeight = 56.dp
private val TableTopGap = 4.dp
