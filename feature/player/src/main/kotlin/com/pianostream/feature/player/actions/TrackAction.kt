package com.pianostream.feature.player.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import com.pianostream.core.data.downloads.DownloadState

/**
 * The action vocabulary of [TrackActionsSheet] — every row the sheet can
 * show. Behaviour stays outside the sheet: a tap only reports the action
 * through `onAction`, so the same menu can mount on the player, search
 * rows, or library rows without knowing about repositories or the player.
 */
enum class TrackAction {
    ToggleFavorite,
    AddToPlaylist,
    AddToQueue,
    Download,
    GoToAlbum,
    GoToArtist,
    Share,
    SleepTimer,
}

/** Rows render in declaration order, filtered to the caller's enabled set. */
internal fun actionRows(enabled: Set<TrackAction>): List<TrackAction> =
    TrackAction.entries.filter(enabled::contains)

internal fun TrackAction.label(
    isFavorite: Boolean,
    downloadState: DownloadState? = null,
): String = when (this) {
    TrackAction.ToggleFavorite ->
        if (isFavorite) "Remove from collection" else "Save to collection"
    TrackAction.AddToPlaylist -> "Add to playlist"
    TrackAction.AddToQueue -> "Add to queue"
    TrackAction.Download -> when (downloadState) {
        DownloadState.DONE -> "Remove download"
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> "Downloading…"
        DownloadState.FAILED -> "Retry download"
        null -> "Download"
    }
    TrackAction.GoToAlbum -> "Go to album"
    TrackAction.GoToArtist -> "Go to artist"
    TrackAction.Share -> "Share"
    TrackAction.SleepTimer -> "Sleep timer"
}

internal fun TrackAction.icon(
    isFavorite: Boolean,
    downloadState: DownloadState? = null,
): ImageVector = when (this) {
    TrackAction.ToggleFavorite ->
        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder
    TrackAction.AddToPlaylist -> Icons.AutoMirrored.Filled.PlaylistAdd
    TrackAction.AddToQueue -> Icons.AutoMirrored.Filled.QueueMusic
    TrackAction.Download ->
        if (downloadState == DownloadState.DONE) {
            Icons.Filled.DownloadDone
        } else {
            Icons.Filled.Download
        }
    TrackAction.GoToAlbum -> Icons.Filled.Album
    TrackAction.GoToArtist -> Icons.Filled.Person
    TrackAction.Share -> Icons.Filled.Share
    TrackAction.SleepTimer -> Icons.Filled.Bedtime
}
