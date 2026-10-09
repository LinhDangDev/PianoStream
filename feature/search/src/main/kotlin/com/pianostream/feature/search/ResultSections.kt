package com.pianostream.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora
import com.pianostream.core.youtube.api.YouTubeItem

/**
 * Section plumbing for the Spotify-style results layout — kind labels, the
 * "See all" section header, and the recent-searches rows. Kind mapping
 * follows `YouTubeItem.Kind`; a row without kind info renders as "Song".
 */

fun SearchViewModel.TypeFilter.label(): String =
    when (this) {
        SearchViewModel.TypeFilter.ALL -> "All"
        SearchViewModel.TypeFilter.SONGS -> "Songs"
        SearchViewModel.TypeFilter.VIDEOS -> "Videos"
        SearchViewModel.TypeFilter.ARTISTS -> "Artists"
        SearchViewModel.TypeFilter.ALBUMS -> "Albums"
        SearchViewModel.TypeFilter.PLAYLISTS -> "Playlists"
    }

/** Singular kind label used on the Top-result card and for no-result copy. */
fun YouTubeItem.Kind.singularLabel(): String =
    when (this) {
        YouTubeItem.Kind.TRACK -> "Song"
        YouTubeItem.Kind.VIDEO -> "Video"
        YouTubeItem.Kind.ALBUM -> "Album"
        YouTubeItem.Kind.ARTIST -> "Artist"
        YouTubeItem.Kind.PLAYLIST -> "Playlist"
    }

/** The chip a kind belongs under — the mapping "See all" applies. */
fun YouTubeItem.Kind.toTypeFilter(): SearchViewModel.TypeFilter =
    when (this) {
        YouTubeItem.Kind.TRACK -> SearchViewModel.TypeFilter.SONGS
        YouTubeItem.Kind.VIDEO -> SearchViewModel.TypeFilter.VIDEOS
        YouTubeItem.Kind.ARTIST -> SearchViewModel.TypeFilter.ARTISTS
        YouTubeItem.Kind.ALBUM -> SearchViewModel.TypeFilter.ALBUMS
        YouTubeItem.Kind.PLAYLIST -> SearchViewModel.TypeFilter.PLAYLISTS
    }

fun YouTubeItem.Kind.matches(filter: SearchViewModel.TypeFilter): Boolean =
    filter == SearchViewModel.TypeFilter.ALL || toTypeFilter() == filter

/** Section header with an optional trailing action ("See all", "Clear all"). */
@Composable
fun ResultSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionHeader(title, Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** One "Recent searches" row — clock icon, query, trailing ✕ to remove. */
@Composable
fun RecentSearchRow(
    query: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = Vinyl.GoldDim,
            modifier = Modifier.size(20.dp),
        )
        Text(
            query,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = Aurora.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Remove $query from recent searches",
            tint = Vinyl.GoldDim,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onRemove),
        )
    }
}
