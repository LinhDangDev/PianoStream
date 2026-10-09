package com.pianostream.core.media.audiofx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.settings.PlaybackPrefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/** Per-buffer volatile gain, clip protection, graceful bypass. */
class NormalizeGainProcessorTest {

    private fun format(
        encoding: Int = C.ENCODING_PCM_16BIT,
        channels: Int = 2,
    ) = AudioProcessor.AudioFormat(48_000, channels, encoding)

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
    fun always_active_on_pcm16_for_live_toggles() {
        val p = NormalizeGainProcessor(PlaybackFxState())
        p.configure(format())
        assertThat(p.isActive).isTrue()
    }

    @Test
    fun non_16bit_bypasses_instead_of_rejecting() {
        val p = NormalizeGainProcessor(PlaybackFxState())
        assertThat(p.configure(format(encoding = C.ENCODING_PCM_FLOAT)))
            .isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
    }

    @Test
    fun unity_default_passes_samples_through() {
        val p = NormalizeGainProcessor(PlaybackFxState())
        p.configure(format())
        p.flush() // sink promotes pending→active formats between configure and queueInput
        p.queueInput(pcm16(100, -100, 32767, -32768))
        assertThat(readShorts(p.getOutput()))
            .containsExactly(100, -100, 32767, -32768).inOrder()
    }

    @Test
    fun preamp_gain_scales_and_clips() {
        val state = PlaybackFxState()
        // +6 dB ≈ ×1.995
        state.update(PlaybackPrefs(preampDb = 6.0))
        val p = NormalizeGainProcessor(state)
        p.configure(format())
        p.flush()
        p.queueInput(pcm16(1000, -1000, 20_000))
        assertThat(readShorts(p.getOutput()))
            .containsExactly(1995, -1995, 32767).inOrder()
    }

    @Test
    fun toggle_off_mid_stream_unity_next_buffer() {
        val state = PlaybackFxState()
        state.update(PlaybackPrefs(preampDb = 6.0))
        val p = NormalizeGainProcessor(state)
        p.configure(format())
        p.flush()
        p.queueInput(pcm16(1000))
        p.getOutput()
        state.update(PlaybackPrefs(normalizeVolume = false, preampDb = 6.0))
        p.queueInput(pcm16(1000))
        assertThat(readShorts(p.getOutput())).containsExactly(1000)
    }
}
