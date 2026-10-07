package com.pianostream.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianostream.core.designsystem.theme.Aurora

/** One top-level destination in [PillNavBar]. */
data class PillNavItem(
    val icon: ImageVector,
    val label: String,
    val contentDescription: String = "$label tab",
)

/**
 * Aurora bottom bar (ported from Melody's PillNavBar): a full-width frosted
 * strip — `GlassStrong` fill, 0.5dp hairline top edge — with centered icon +
 * label per tab. Icon 22dp, label 10sp; the selected tab tints accent.
 */
@Composable
fun PillNavBar(
    items: List<PillNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Aurora.GlassStrong)
            .border(width = 0.5.dp, color = Aurora.Hairline)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint = if (selected) Aurora.Accent else Aurora.TextSecondary
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 6.dp),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.contentDescription,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        color = tint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
