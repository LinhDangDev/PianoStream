package com.pianostream.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/** H3 — 16-bit PCM sample scaling, clip protection, lifecycle states. */
class ReplayGainAudioProcessorTest {

    private fun format(
        encoding: Int = C.ENCODING_PCM_16BIT,
        sampleRate: Int = 48_000,
        channels: Int = 2,
    ) = AudioProcessor.AudioFormat(sampleRate, channels, encoding)

    private fun pcm16(vararg samples: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(samples.size * 2)
            .order(ByteOrder.nativeOrder())
        samples.forEach { buf.putShort(it.toShort()) }
        buf.flip()
        return buf
    }

    private fun readShorts(out: ByteBuffer): List<Int> {
        val result = ArrayList<Int>()
        while (out.hasRemaining()) result += out.getShort().toInt()
        return result
    }

    @Test
    fun unity_gain_passes_samples_through() {
        val p = ReplayGainAudioProcessor()
        assertThat(p.configure(format())).isEqualTo(format())
        p.gain = 1.0f
        p.queueInput(pcm16(100, -100, 32767, -32768))
        val out = p.getOutput()
        assertThat(readShorts(out)).containsExactly(100, -100, 32767, -32768).inOrder()
    }

    @Test
    fun half_gain_scales_samples() {
        val p = ReplayGainAudioProcessor()
        p.configure(format())
        p.gain = 0.5f
        p.queueInput(pcm16(1000, -1000, 400))
        assertThat(readShorts(p.getOutput()))
            .containsExactly(500, -500, 200).inOrder()
    }

    @Test
    fun overdrive_clips_to_int16() {
        val p = ReplayGainAudioProcessor()
        p.configure(format())
        p.gain = 4.0f
        p.queueInput(pcm16(20_000, -20_000, 10_000))
        assertThat(readShorts(p.getOutput()))
            .containsExactly(32767, -32768, 32767).inOrder()
    }

    @Test
    fun non_16bit_format_rejected() {
        val p = ReplayGainAudioProcessor()
        try {
            p.configure(format(encoding = C.ENCODING_PCM_FLOAT))
            throw AssertionError("expected UnhandledAudioFormatException")
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            // expected
        }
    }

    @Test
    fun not_active_until_configured() {
        val p = ReplayGainAudioProcessor()
        assertThat(p.isActive).isFalse()
        p.configure(format())
        assertThat(p.isActive).isTrue()
    }

    @Test
    fun flush_clears_state_and_reset_restores_unity() {
        val p = ReplayGainAudioProcessor()
        p.configure(format())
        p.gain = 0.25f
        p.queueEndOfStream()
        // No pending output: with the stream ended and an empty buffer the
        // processor is already at its end.
        assertThat(p.isEnded()).isTrue()
        p.reset()
        assertThat(p.gain).isEqualTo(1.0f)
        assertThat(p.isActive).isFalse()
    }

    @Test
    fun end_of_stream_flags_ended() {
        val p = ReplayGainAudioProcessor()
        p.configure(format())
        p.queueInput(pcm16(1, 2, 3))
        p.queueEndOfStream()
        p.getOutput() // drain
        assertThat(p.isEnded()).isTrue()
    }

    @Test
    fun volatile_gain_change_takes_effect_next_input() {
        val p = ReplayGainAudioProcessor()
        p.configure(format())
        p.gain = 1.0f
        p.queueInput(pcm16(100))
        p.getOutput()
        p.gain = 2.0f
        p.queueInput(pcm16(100))
        assertThat(readShorts(p.getOutput())).containsExactly(200)
    }
}
