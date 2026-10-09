package com.pianostream.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pianostream.core.designsystem.theme.Aurora

// Per-bar loop timing (ms) — slightly different periods + phase offsets keep
// the three bars from ever lining up, like Spotify's now-playing indicator.
private val BarDurationsMs = intArrayOf(430, 560, 490)
private val BarOffsetsMs = intArrayOf(0, 150, 80)
private val PausedFractions = listOf(0.45f, 0.65f, 0.5f)

/**
 * Three vertical bars that loop through heights while [animate] is true and
 * freeze at mid heights when it is false (paused state). Sized by the caller:
 * the bars grow from the bottom edge of whatever height [modifier] gives them.
 *
 * Used as the "now playing" overlay on [TrackRow]'s artwork thumb.
 */
@Composable
fun EqualizerBars(
    animate: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Aurora.Accent,
) {
    val fractions = if (animate) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        List(3) { i ->
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = BarDurationsMs[i]),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(BarOffsetsMs[i]),
                ),
                label = "bar$i",
            ).value
        }
    } else {
        PausedFractions
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        fractions.forEach { fraction ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(barColor)
            )
        }
    }
}
