package com.pianostream.core.media.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * PCM-16 ReplayGain processor (H2/H3): applies a volatile linear gain
 * sample-wise across every channel of the input buffer. Installed into the
 * RenderersFactory at service start — when `replayGainMode = OFF` the
 * processor is not installed at all (a restart is required to re-enable).
 *
 * The crossfade secondary carries its OWN instance, set to the incoming
 * track's `gainDb` before `play()` (H3).
 */
class ReplayGainAudioProcessor : AudioProcessor {

    /** Linear gain multiplier; volatile so the player thread can set it
     *  from the session thread mid-playback. */
    @Volatile
    var gain: Float = 1.0f

    private var format: AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        format = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = format !== AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        replaceBuffer(remaining)

        val g = gain
        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.short.toInt()
            val scaled = (sample * g).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(scaled.toShort())
        }
        buffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        buffer = EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        format = AudioProcessor.AudioFormat.NOT_SET
        gain = 1.0f
    }

    private fun replaceBuffer(size: Int) {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
    }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0)
            .order(java.nio.ByteOrder.nativeOrder())
    }
}
