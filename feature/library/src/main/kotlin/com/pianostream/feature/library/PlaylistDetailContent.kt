package com.pianostream.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.pianostream.core.data.playback.model.Playlist
import com.pianostream.core.data.playback.model.QueueItem
import com.pianostream.core.designsystem.art.ArtworkUrls
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.TrackRow
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora
import kotlin.math.roundToInt

/**
 * Playlist detail v2 (reference: Spotify's playlist page) — a centred hero
 * (generated 2×2 artwork mosaic, title, "N songs · Xh Ym", optional
 * description), a Play / Shuffle / ⋯ action row, then index-numbered track
 * rows with remove + long-press drag-to-reorder.
 *
 * Reorder is hand-rolled on `detectDragGesturesAfterLongPress` because the
 * `sh.calvin.reorderable` dep only exists on `:feature:player`'s classpath —
 * the lifted-row/neighbour-shift visual language still mirrors QueueSheet.
 */
@Composable
internal fun PlaylistDetailContent(
    playlist: Playlist?,
    /** The playlist's full track list in position order. */
    tracks: List<QueueItem>,
    /** Active library-search query — filters the visible rows. */
    query: String,
    onBack: () -> Unit,
    onPlayQueue: (List<QueueItem>, Int) -> Unit,
    onRename: (String) -> Unit,
    onEditDetails: (String, String) -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    /** Ranks inside `tracks` (index space of the full ordered list). */
    onMoveItem: (Int, Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    val visible = remember(tracks, query) {
        if (query.isBlank()) tracks
        else tracks.filter { it.matchesLibraryQuery(query) }
    }
    val coverUris = playlist?.coverUris?.takeIf { it.isNotEmpty() }
        ?: tracks.mapNotNull { it.artworkUri }.take(4)

    // --- drag state ---------------------------------------------------------
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val fallbackRowPx = with(density) { 72.dp.toPx() }
    val rowPx = if (rowHeightPx > 0f) rowHeightPx else fallbackRowPx
    val dragTarget = if (dragFrom >= 0) {
        (dragFrom + (dragOffsetPx / rowPx).roundToInt())
            .coerceIn(0, visible.lastIndex)
    } else {
        -1
    }

    fun commitDrag() {
        val to = dragTarget
        if (dragFrom >= 0 && to >= 0 && to != dragFrom) {
            // `move` works in rank space — map visible rows back to indices
            // of the full list so reordering stays correct while filtered.
            val fromRank = tracks.indexOf(visible[dragFrom])
            val toRank = tracks.indexOf(visible[to])
            if (fromRank >= 0 && toRank >= 0) onMoveItem(fromRank, toRank)
        }
        dragFrom = -1
        dragOffsetPx = 0f
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar — back only; actions live in the hero's ⋯ per the spec.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to playlists",
                    tint = Aurora.TextSecondary,
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            // --- hero -------------------------------------------------------
            item(key = "hero") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlaylistCoverMosaic(
                        artworkUris = coverUris,
                        title = playlist?.name.orEmpty(),
                        size = 148.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        playlist?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Aurora.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playlistHeaderLine(tracks),
                        style = MaterialTheme.typography.bodySmall,
                        color = Aurora.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    playlist?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aurora.TextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // --- action row: Play / Shuffle / ⋯ ---------------------
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    ) {
                        Button(
                            onClick = { onPlayQueue(tracks, 0) },
                            enabled = tracks.isNotEmpty(),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Vinyl.Gold,
                                contentColor = Aurora.Bg,
                                disabledContainerColor = Vinyl.GoldDim.copy(alpha = 0.4f),
                                disabledContentColor = Aurora.Bg,
                            ),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                if (tracks.isNotEmpty()) onPlayQueue(tracks.shuffled(), 0)
                            },
                            enabled = tracks.isNotEmpty(),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Vinyl.GoldDim),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Vinyl.Gold,
                            ),
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Shuffle")
                        }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Playlist actions",
                                    tint = Aurora.TextSecondary,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = Vinyl.BgTop,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename", color = Vinyl.Gold) },
                                    onClick = { menuOpen = false; renameOpen = true },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Edit details", color = Aurora.TextPrimary)
                                    },
                                    onClick = { menuOpen = false; editOpen = true },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Delete playlist", color = Aurora.TextSecondary)
                                    },
                                    onClick = { menuOpen = false; deleteOpen = true },
                                )
                            }
                        }
                    }
                }
            }

            // --- track rows ---------------------------------------------------
            if (visible.isEmpty()) {
                item(key = "empty") {
                    if (query.isNotBlank() && tracks.isNotEmpty()) {
                        EmptyState(
                            "Nothing found in library",
                            "No matches for \"$query\".",
                        )
                    } else {
                        EmptyState(
                            "Empty playlist",
                            "Add tracks from the Tracks or Favourites tabs.",
                        )
                    }
                }
            } else {
                itemsIndexed(visible, key = { _, t -> t.mediaId }) { idx, t ->
                    val lift = when {
                        idx == dragFrom -> dragOffsetPx
                        dragFrom >= 0 && dragTarget >= 0 &&
                            idx > dragFrom && idx <= dragTarget -> -rowPx
                        dragFrom >= 0 && dragTarget >= 0 &&
                            idx >= dragTarget && idx < dragFrom -> rowPx
                        else -> 0f
                    }
                    PlaylistTrackRow(
                        index = idx + 1,
                        item = t,
                        isDragging = idx == dragFrom,
                        offsetY = lift,
                        onClick = { onPlayQueue(tracks, tracks.indexOf(t)) },
                        onRemove = { onRemoveTrack(t.mediaId) },
                        onMeasureRow = { h -> if (rowHeightPx == 0f) rowHeightPx = h },
                        dragModifier = Modifier.pointerInput(t.mediaId) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.LongPress,
                                    )
                                    dragFrom = idx
                                    dragOffsetPx = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx += dragAmount.y
                                },
                                onDragEnd = { commitDrag() },
                                onDragCancel = {
                                    dragFrom = -1
                                    dragOffsetPx = 0f
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        PlaylistNameDialog(
            title = "Rename playlist",
            initial = playlist?.name.orEmpty(),
            confirmLabel = "Save",
            onConfirm = { onRename(it); renameOpen = false },
            onDismiss = { renameOpen = false },
        )
    }
    if (editOpen) {
        EditPlaylistDetailsDialog(
            initialName = playlist?.name.orEmpty(),
            initialDescription = playlist?.description.orEmpty(),
            coverUris = coverUris,
            title = playlist?.name.orEmpty(),
            onConfirm = { name, desc -> onEditDetails(name, desc); editOpen = false },
            onDismiss = { editOpen = false },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = Vinyl.BgTop,
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "\"${playlist?.name.orEmpty()}\" and its track list are removed. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteOpen = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Aurora.Error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Cancel") }
            },
        )
    }
}

/** "N songs · Xh Ym" — duration half only when at least one track reports it. */
private fun playlistHeaderLine(tracks: List<QueueItem>): String {
    val count = tracks.size
    val songWord = if (count == 1) "song" else "songs"
    val totalMs = tracks.mapNotNull { it.durationMs }.sum()
    if (totalMs <= 0L) return "$count $songWord"
    val totalMin = totalMs / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    val dur = when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "${m}m"
        else -> "<1m"
    }
    return "$count $songWord · $dur"
}

/**
 * Generated cover: 2×2 mosaic of the first tracks' artwork (URIs repeat to
 * fill empty cells), or a vinyl record when nothing carries art yet.
 */
@Composable
private fun PlaylistCoverMosaic(
    artworkUris: List<String>,
    title: String,
    size: Dp,
) {
    val uris = artworkUris.mapNotNull { ArtworkUrls.sanitize(it) }.take(4)
    Box(
        Modifier
            .size(size)
            .shadow(8.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .background(Aurora.SurfaceLight)
            .border(0.5.dp, Aurora.Hairline, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            uris.isEmpty() -> VinylDisc(
                artworkUri = null,
                title = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                artworkContentDescription = null,
            )
            uris.size == 1 -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(uris[0]).build(),
                contentDescription = "Playlist cover for $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Column(Modifier.fillMaxSize()) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0 until 2) {
                            val uri = uris[(row * 2 + col) % uris.size]
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(uri)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One detail row: index + TrackRow + remove + drag handle. [offsetY] slides
 * the row while a neighbour is dragged; the handle owns the long-press
 * gesture.
 */
@Composable
private fun PlaylistTrackRow(
    index: Int,
    item: QueueItem,
    isDragging: Boolean,
    offsetY: Float,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMeasureRow: (Float) -> Unit,
    dragModifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = offsetY }
            .onGloballyPositioned { onMeasureRow(it.size.height.toFloat()) }
            .then(
                if (isDragging) {
                    Modifier
                        .padding(horizontal = 8.dp)
                        .shadow(10.dp, RoundedCornerShape(16.dp), clip = false)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF2A2D32), Color(0xFF1B1C1F)),
                            ),
                        )
                        .border(
                            0.5.dp,
                            Vinyl.Gold.copy(alpha = 0.45f),
                            RoundedCornerShape(16.dp),
                        )
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            "$index",
            style = MaterialTheme.typography.bodySmall,
            color = Aurora.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp),
        )
        Box(Modifier.weight(1f)) {
            TrackRow(
                title = item.title,
                subtitle = item.artist,
                artworkUri = item.artworkUri,
                durationMs = item.durationMs,
                onClick = onClick,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRemove) {
                            Icon(
                                Icons.Outlined.RemoveCircleOutline,
                                contentDescription = "Remove from playlist",
                                tint = Aurora.TextSecondary,
                            )
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(40.dp)
                                .then(dragModifier),
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
}

/**
 * Edit-details dialog — name + free-text description. The cover is not
 * user-settable: it's generated from the first tracks' artwork, which the
 * note line states (and the small preview shows).
 */
@Composable
internal fun EditPlaylistDetailsDialog(
    initialName: String,
    initialDescription: String,
    coverUris: List<String>,
    title: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
        title = { Text("Edit details", color = Aurora.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCoverMosaic(
                        artworkUris = coverUris,
                        title = title,
                        size = 72.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Cover is generated from track artwork",
                        style = MaterialTheme.typography.bodySmall,
                        color = Aurora.TextSecondary,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name", color = Aurora.TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Aurora.TextPrimary,
                        unfocusedTextColor = Aurora.TextPrimary,
                        cursorColor = Vinyl.Gold,
                        focusedBorderColor = Vinyl.Gold,
                        unfocusedBorderColor = Aurora.Hairline,
                    ),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    minLines = 2,
                    placeholder = { Text("Description", color = Aurora.TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Aurora.TextPrimary,
                        unfocusedTextColor = Aurora.TextPrimary,
                        cursorColor = Vinyl.Gold,
                        focusedBorderColor = Vinyl.Gold,
                        unfocusedBorderColor = Aurora.Hairline,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
