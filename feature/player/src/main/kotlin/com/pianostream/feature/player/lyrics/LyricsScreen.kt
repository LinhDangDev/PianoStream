package com.pianostream.feature.player.lyrics

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Idle grace before auto-scroll retakes the list after a user drag. */
private const val USER_SCROLL_RESUME_MS = 2_000L

/** Vertical anchor for the current line, measured from the top edge. */
private const val CURRENT_LINE_ANCHOR = 0.38f

/**
 * Full-screen synced-lyrics view, modelled on Spotify's lyrics sheet: a
 * centred column of lines pinned to the playback position, the live line in
 * gold, tap any line to seek. All data arrives as parameters ([LyricsUiState]
 * + [positionMs]) so the screen composes without a player; the integrator
 * wires position and callbacks from `PlayerViewModel`.
 *
 * Auto-scroll holds the current line ~38% from the top. A user drag suspends
 * it (a "Jump to current line" pill appears); it resumes after ~2 s idle.
 */
@Composable
fun LyricsScreen(
    state: LyricsUiState,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Aurora.backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        LyricsTopBar(
            title = state.title,
            artist = state.artist,
            onClose = onClose,
            onShare = onShare,
        )
        when {
            state.lines == null && state.plainText == null ->
                LoadingBody(Modifier.weight(1f))
            state.lines != null ->
                SyncedBody(
                    lines = state.lines,
                    title = state.title,
                    positionMs = positionMs,
                    onSeekTo = onSeekTo,
                    modifier = Modifier.weight(1f),
                )
            state.plainText.isNullOrBlank() ->
                EmptyBody(title = state.title, modifier = Modifier.weight(1f))
            else ->
                PlainBody(plainText = state.plainText, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LyricsTopBar(
    title: String,
    artist: String,
    onClose: () -> Unit,
    onShare: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(56.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Close lyrics",
                tint = Aurora.TextPrimary,
            )
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Aurora.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    artist,
                    fontSize = 12.sp,
                    color = Aurora.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShare != null) {
            IconButton(onClick = onShare, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Share lyrics",
                    tint = Vinyl.GoldDim,
                )
            }
        }
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Vinyl.Gold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Loading lyrics…",
            style = MaterialTheme.typography.bodyMedium,
            color = Aurora.TextSecondary,
        )
    }
}

@Composable
private fun EmptyBody(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VinylDisc(
            artworkUri = null,
            title = title,
            modifier = Modifier.size(180.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "No lyrics found",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Aurora.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Synced lyrics show up here when the track has them.",
            style = MaterialTheme.typography.bodyMedium,
            color = Aurora.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlainBody(plainText: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Text(
            plainText,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium,
            color = Aurora.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SyncedBody(
    lines: List<LyricsUiState.Line>,
    title: String,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        EmptyBody(title = title, modifier = modifier)
        return
    }

    val listState = rememberLazyListState()
    var userOverride by remember { mutableStateOf(false) }

    // Last cue that started at or before the playhead; -1 before the first.
    val currentIndex = lines.indexOfLast { it.atMs <= positionMs }

    // Only real drags come through interactionSource — our own scroll
    // animations don't — so this cleanly detects "user is steering".
    LaunchedEffect(listState) {
        var resumeJob: Job? = null
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    userOverride = true
                    resumeJob?.cancel()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    resumeJob = launch {
                        delay(USER_SCROLL_RESUME_MS)
                        // A fling can outlive the release; wait it out.
                        while (listState.isScrollInProgress) delay(300)
                        userOverride = false
                    }
                }
            }
        }
    }

    LaunchedEffect(lines, currentIndex, userOverride, listState) {
        if (userOverride) return@LaunchedEffect
        // Seeking back before the first cue re-anchors on line 0.
        listState.scrollLineToAnchor(currentIndex.coerceAtLeast(0), CURRENT_LINE_ANCHOR)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Room for the first lines to rest at the anchor and for the
                // last ones to scroll up to it.
                top = (maxHeight * CURRENT_LINE_ANCHOR - 14.dp).coerceAtLeast(0.dp),
                bottom = maxHeight * 0.65f,
            ),
        ) {
            items(
                count = lines.size,
                key = { it },
                contentType = { "lyric_line" },
            ) { i ->
                LyricLine(
                    text = lines[i].text,
                    isSung = i < currentIndex,
                    isCurrent = i == currentIndex,
                    onClick = {
                        userOverride = false
                        onSeekTo(lines[i].atMs)
                    },
                )
            }
        }

        if (userOverride && currentIndex >= 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Aurora.SurfaceLight)
                    .clickable { userOverride = false }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "Jump to current line",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Vinyl.Gold,
                )
            }
        }
    }
}

@Composable
private fun LyricLine(
    text: String,
    isSung: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        when {
            isCurrent -> Vinyl.Gold
            isSung -> Aurora.TextSecondary
            else -> Aurora.TextSecondary.copy(alpha = 0.55f)
        },
        label = "lyricColor",
    )
    Text(
        // Blank LRC timestamps mark instrumental breaks; Spotify shows "♪".
        text = text.ifBlank { "♪" },
        color = color,
        fontSize = if (isCurrent) 20.sp else 16.sp,
        lineHeight = if (isCurrent) 26.sp else 22.sp,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 9.dp),
    )
}

/**
 * Glides item [index] so its centre sits [anchorFraction] down the viewport.
 * Positions come from `layoutInfo` (item offsets are relative to
 * `viewportStartOffset`), so no scrollToItem-offset guessing: jump far-away
 * items into view first, let a frame lay them out, then scroll by the exact
 * measured delta.
 */
private suspend fun LazyListState.scrollLineToAnchor(index: Int, anchorFraction: Float) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index, -(layoutInfo.viewportSize.height * anchorFraction).roundToInt())
        withFrameNanos { }
    }
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val anchorOffset =
        layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height * anchorFraction -
            info.size / 2f
    animateScrollBy(info.offset - anchorOffset)
}

@Preview(backgroundColor = 0xFF1B1C1F, showBackground = true)
@Composable
private fun LyricsScreenSyncedPreview() {
    val demoLines = listOf(
        LyricsUiState.Line(0, "Meet me in the afterglow"),
        LyricsUiState.Line(4_000, "Under neon skies we go"),
        LyricsUiState.Line(8_000, "Hold the moment, let it flow"),
        LyricsUiState.Line(12_000, "Like a record spinning slow"),
        LyricsUiState.Line(16_000, ""),
        LyricsUiState.Line(20_000, "And the vinyl knows our song"),
        LyricsUiState.Line(24_000, "Playing all night long"),
        LyricsUiState.Line(28_000, "Gold on charcoal, spinning on"),
        LyricsUiState.Line(32_000, "Till the needle finds the dawn"),
        LyricsUiState.Line(36_000, "Meet me in the afterglow"),
    )
    LyricsScreen(
        state = LyricsUiState(
            title = "Afterglow",
            artist = "PianoStream",
            lines = demoLines,
            plainText = null,
        ),
        positionMs = 22_000,
        onSeekTo = {},
        onClose = {},
        onShare = {},
    )
}

@Preview(backgroundColor = 0xFF1B1C1F, showBackground = true)
@Composable
private fun LyricsScreenEmptyPreview() {
    LyricsScreen(
        state = LyricsUiState(
            title = "Silent Track",
            artist = "Unknown",
            lines = null,
            plainText = "",
        ),
        positionMs = 0,
        onSeekTo = {},
        onClose = {},
    )
}
