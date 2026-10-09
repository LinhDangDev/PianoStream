package com.pianostream.core.media.audiofx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Toggleable N→1 mono downmix for the `monoAudio` preference.
 *
 * The spec asks for a `ChannelMixingAudioProcessor`-style downmix on the
 * chain the factory already builds; a fixed-matrix processor cannot be
 * toggled at runtime, so this `BaseAudioProcessor` reads
 * [PlaybackFxState.monoEnabled] inside `onConfigure` instead — the flag
 * engages/disengages at the next audio-sink reconfiguration (next track
 * boundary or seek), which is also the granularity `replayGainMode`
 * changes already apply at.
 *
 * Disabled / mono input / non-16-bit input ⇒ `NOT_SET`, so the sink
 * bypasses the processor entirely (zero-copy passthrough). The downmix is
 * a per-frame channel average — the [0.5, 0.5] stereo→mono matrix's exact
 * equivalent — and cannot clip (an average never exceeds its inputs).
 */
class MonoAudioProcessor(
    private val state: PlaybackFxState = PlaybackFxState.shared,
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        return if (
            state.monoEnabled &&
            inputAudioFormat.channelCount > 1 &&
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        ) {
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                /* channelCount = */ 1,
                inputAudioFormat.encoding,
            )
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        if (!inputBuffer.hasRemaining() || channels <= 1) return
        // One output short per input frame: N channels in → 1 channel out.
        val out = replaceOutputBuffer(inputBuffer.remaining() / channels)
        while (inputBuffer.hasRemaining()) {
            var sum = 0
            repeat(channels) { sum += inputBuffer.short.toInt() }
            out.putShort((sum / channels).toShort())
        }
        out.flip()
    }
}
