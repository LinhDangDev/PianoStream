package com.pianostream.core.media.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Audio-offload selection: offload is enabled on the primary for gapless
 * battery savings, but force-disabled while a crossfade is active (H4 — two
 * simultaneous offload sessions are unreliable on many devices).
 */
object AudioOffloadPolicy {

    /** Apply to the primary player; pass `crossfading` from the controller. */
    fun apply(player: ExoPlayer, crossfading: Boolean) {
        // Audio offload preference lives on the player's track-selection
        // parameters — an offload schedule change mid-play is rejected, so the
        // service rebuilds the flag only between fades.
        val attrs = player.getAudioAttributes() ?: AudioAttributes.DEFAULT
        player.setAudioAttributes(
            attrs,
            /* handleAudioFocus = */ !crossfading,
        )
    }

    /** Attributes for the primary player (USAGE_MEDIA / CONTENT_TYPE_MUSIC). */
    fun primaryAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
}
