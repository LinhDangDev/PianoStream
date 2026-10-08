package com.pianostream.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.media.video.VideoQualityStore

/**
 * 16:9 video surface that takes the turntable's square slot when the
 * current item plays through `pianostream://video/` (YouTube Music's
 * video mode on a dark sheet). TextureView-backed so the rounded
 * neumorphic frame actually clips the picture. The slot keeps the same
 * size as the platter, so title/seek/controls below never move.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPanel(player: Player?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(10.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF2F3237), Color(0xFF131416))),
                )
                .border(1.dp, Vinyl.Gold.copy(alpha = 0.22f), RoundedCornerShape(20.dp)),
        ) {
            if (player != null) {
                PlayerSurface(
                    player = player,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Quality chip pinned to the video surface's top-right corner — a small
 * charcoal pill labeled with the current tier ("1080p ▾", "Auto ▾") that
 * opens a gold-on-charcoal dropdown of [VideoQualityStore.TIERS]. Same
 * chrome language as [SongVideoToggle] (recessed #0F1012 pill, hairline
 * border, gold text). Tiers the response lacks are handled at resolve time
 * (at-or-below pick), so the menu lists every rung.
 */
@Composable
internal fun VideoQualityChip(
    currentItag: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF0F1012).copy(alpha = 0.88f))
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.07f),
                    RoundedCornerShape(50),
                )
                .clickable(onClickLabel = "Choose video quality") { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${VideoQualityStore.labelFor(currentItag)} ▾",
                color = Vinyl.Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Color(0xFF141518),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.border(
                1.dp,
                Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(14.dp),
            ),
        ) {
            VideoQualityStore.TIERS.forEach { (label, itag) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (itag == currentItag) Vinyl.Gold
                            else Vinyl.GoldDim,
                            fontSize = 13.sp,
                            fontWeight = if (itag == currentItag) FontWeight.SemiBold
                            else FontWeight.Medium,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(itag)
                    },
                )
            }
        }
    }
}

/**
 * "Song | Video" segmented pill in the expanded player's top chrome —
 * gold active segment on a recessed charcoal groove, the way YouTube
 * Music frames the toggle. Only rendered for YouTube-backed items.
 */
@Composable
internal fun SongVideoToggle(
    videoMode: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .width(156.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF0F1012))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(50),
            )
            .padding(3.dp),
    ) {
        Segment(
            label = "Song",
            selected = !videoMode,
            onSelect = { onChange(false) },
            modifier = Modifier.weight(1f),
        )
        Segment(
            label = "Video",
            selected = videoMode,
            onSelect = { onChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) {
                    Modifier.background(
                        Brush.horizontalGradient(listOf(Color(0xFF9C7556), Vinyl.Gold)),
                    )
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF1B1C1F) else Vinyl.GoldDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
