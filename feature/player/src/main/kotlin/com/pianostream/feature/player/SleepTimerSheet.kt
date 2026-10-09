package com.pianostream.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.formatDuration
import com.pianostream.core.designsystem.theme.Aurora

/**
 * Sleep-timer bottom sheet (Interface Freeze M-track): Spotify's sleep
 * timer sheet is the reference — a grid of duration chips plus a running
 * countdown once a timer is armed.
 *
 * Presentation only. `SleepTimerUi` is deliberately a local, self-contained
 * contract so this sheet compiles standalone: the integrator maps the real
 * sleep-timer state (`PlayerViewModel.sleepTimer`, owned by another agent)
 * into it, translates chip taps through [onPick] into timer ops, and calls
 * [onCancel] for "Turn off timer". [onDismiss] drives sheet visibility.
 */
data class SleepTimerUi(
    /** True while a countdown or end-of-track arm is running. */
    val active: Boolean,
    /** Time left until pause (for END_OF_TRACK, remaining track time). */
    val remainingMs: Long,
    /** The armed mode when [active], else the last-picked chip. */
    val mode: Mode,
) {
    enum class Mode(val minutes: Int?) {
        MIN5(5),
        MIN10(10),
        MIN15(15),
        MIN30(30),
        MIN45(45),
        MIN60(60),
        END_OF_TRACK(null);

        /** Wall-clock duration, or null when "End of track" is track-bound. */
        val durationMs: Long?
            get() = minutes?.times(60_000L)
    }
}

/** Human label for a chip: "5 min" … "60 min", "End of track". */
internal val SleepTimerUi.Mode.chipLabel: String
    get() = minutes?.let { "$it min" } ?: "End of track"

/** Status line shown while a timer runs — "Sleeping in MM:SS" (gold). */
internal fun sleepCountdownText(remainingMs: Long): String =
    "Sleeping in ${formatDuration(remainingMs)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerUi,
    onPick: (SleepTimerUi.Mode) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Vinyl.BgTop,
        dragHandle = { SleepSheetHandle() },
        modifier = modifier,
    ) {
        SleepTimerContent(state = state, onPick = onPick, onCancel = onCancel)
    }
}

/** Gold grabber — same 36x4 `Vinyl.GoldDim` pill QueueSheet draws. */
@Composable
private fun SleepSheetHandle() {
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
}

@Composable
internal fun SleepTimerContent(
    state: SleepTimerUi,
    onPick: (SleepTimerUi.Mode) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Sleep timer",
            color = Vinyl.Gold,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Aurora.Hairline),
        )
        Spacer(Modifier.height(20.dp))

        // Duration grid: three chips per row, then the track-bound option
        // full-width (Spotify lists it apart from the minute picks).
        val minuteModes = SleepTimerUi.Mode.entries
            .filter { it != SleepTimerUi.Mode.END_OF_TRACK }
        minuteModes.chunked(3).forEach { rowModes ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowModes.forEach { m ->
                    SleepChip(
                        label = m.chipLabel,
                        selected = state.mode == m,
                        onClick = { onPick(m) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        SleepChip(
            label = SleepTimerUi.Mode.END_OF_TRACK.chipLabel,
            selected = state.mode == SleepTimerUi.Mode.END_OF_TRACK,
            onClick = { onPick(SleepTimerUi.Mode.END_OF_TRACK) },
            modifier = Modifier.fillMaxWidth(),
            icon = {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (state.mode == SleepTimerUi.Mode.END_OF_TRACK) {
                        Aurora.onAccent
                    } else {
                        Vinyl.GoldDim
                    },
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        Spacer(Modifier.height(22.dp))
        if (state.active) {
            Text(
                sleepCountdownText(state.remainingMs),
                color = Vinyl.Gold,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Aurora.Error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Aurora.Error),
            ) {
                Text(
                    "Turn off timer",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Text(
                "Playback will pause when the timer ends.",
                color = Aurora.TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * One duration chip — a recessed charcoal pill that fills with gold and
 * flips to dark text when selected (the neumorphic "pressed" read of
 * Spotify's selected option).
 */
@Composable
private fun SleepChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Vinyl.Gold else Aurora.SurfaceLight)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(0.5.dp, Aurora.Hairline, RoundedCornerShape(50))
                },
            )
            .clickable(onClickLabel = "Set sleep timer to $label", onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (selected) Aurora.onAccent else Aurora.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
