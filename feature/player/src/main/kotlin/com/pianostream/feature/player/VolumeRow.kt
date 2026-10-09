package com.pianostream.feature.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pianostream.core.designsystem.component.Vinyl
import kotlin.math.roundToInt

// android.media.VOLUME_CHANGED_ACTION / EXTRA_VOLUME_STREAM_TYPE literals —
// the AudioManager constants are hidden on this compileSdk, but the
// broadcast action strings are stable.
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

/**
 * Device volume row for Now Playing (the "near bottom" row Spotify/Apple
 * Music put under the transport): a speaker glyph and a thin [GoldSlider]
 * bound to STREAM_MUSIC. Drags write through AudioManager.setStreamVolume
 * silently (no system toast on every step); hardware-key changes are picked
 * back up via VOLUME_CHANGED_ACTION.
 */
@Composable
internal fun VolumeRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // Follow volume changes that bypass this slider (hardware keys, other
    // volume surfaces) so the track stays in sync.
    DisposableEffect(audioManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                if (stream == AudioManager.STREAM_MUSIC) {
                    volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (volume == 0) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Volume",
            tint = Vinyl.GoldDim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        GoldSlider(
            fraction = (volume.toFloat() / maxVolume).coerceIn(0f, 1f),
            onChange = { fraction ->
                val target = (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                volume = target
            },
            thin = true,
            modifier = Modifier.weight(1f),
        )
    }
}
