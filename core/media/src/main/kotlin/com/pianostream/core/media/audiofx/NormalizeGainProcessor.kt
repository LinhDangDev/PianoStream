package com.pianostream.core.media.audiofx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Live normalize/preamp stage for the `normalizeVolume` + `preampDb`
 * preferences — the light version of volume normalization.
 *
 * `player.volume` is NOT used for this: `CrossfadeController` already owns
 * the primary's volume for fade ramps, so a second writer would fight it.
 * Instead this processor sits after `ReplayGainAudioProcessor` in the
 * chain the factory builds and multiplies PCM-16 samples by
 * [PlaybackFxState.normalizeGainLinear], which it re-reads for every input
 * buffer — a preamp or toggle change applies within the next queued block,
 * not just at track boundaries.
 *
 * Always active on 16-bit input (same contract as
 * `ReplayGainAudioProcessor`) so the flag flips are live; other encodings
 * return `NOT_SET` and are bypassed rather than rejected.
 */
class NormalizeGainProcessor(
    private val state: PlaybackFxState = PlaybackFxState.shared,
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val gain = state.normalizeGainLinear
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.hasRemaining()) {
            val scaled = (inputBuffer.short.toInt() * gain).roundToInt()
            out.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        out.flip()
    }
}
