package com.pianostream.core.media.audiofx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.settings.PlaybackPrefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/** Stereo→mono downmix, bypass semantics, enable flag granularity. */
class MonoAudioProcessorTest {

    private fun enabledState(): PlaybackFxState =
        PlaybackFxState().also { it.update(PlaybackPrefs(monoAudio = true)) }

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
    fun disabled_bypasses_sink_chain() {
        val p = MonoAudioProcessor(PlaybackFxState())
        // NOT_SET ⇒ DefaultAudioSink skips the processor entirely.
        assertThat(p.configure(format()))
            .isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        assertThat(p.isActive).isFalse()
    }

    @Test
    fun enabled_stereo_configures_to_mono() {
        val p = MonoAudioProcessor(enabledState())
        val out = p.configure(format(channels = 2))
        assertThat(out.channelCount).isEqualTo(1)
        assertThat(out.sampleRate).isEqualTo(48_000)
        assertThat(out.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(p.isActive).isTrue()
    }

    @Test
    fun downmix_averages_frames() {
        val p = MonoAudioProcessor(enabledState())
        p.configure(format(channels = 2))
        // configure() only stages pending formats — the sink's flush()
        // promotes them, exactly like a real DefaultAudioSink does.
        p.flush()
        // frames: (1000, 200) → 600, (-400, -600) → -500, (32767, 32765) → 32766
        p.queueInput(pcm16(1000, 200, -400, -600, 32767, 32765))
        assertThat(readShorts(p.getOutput()))
            .containsExactly(600, -500, 32766).inOrder()
    }

    @Test
    fun downmix_multichannel_averages_all_channels() {
        val p = MonoAudioProcessor(enabledState())
        p.configure(format(channels = 4))
        p.flush()
        p.queueInput(pcm16(400, 400, 400, 400, 100, -300, 500, -900))
        assertThat(readShorts(p.getOutput()))
            .containsExactly(400, -150).inOrder()
    }

    @Test
    fun enabled_mono_input_bypasses() {
        val p = MonoAudioProcessor(enabledState())
        assertThat(p.configure(format(channels = 1)))
            .isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
    }

    @Test
    fun enabled_float_input_bypasses_instead_of_rejecting() {
        val p = MonoAudioProcessor(enabledState())
        assertThat(p.configure(format(encoding = C.ENCODING_PCM_FLOAT)))
            .isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
    }

    @Test
    fun flag_flip_takes_effect_at_next_configure() {
        val state = PlaybackFxState()
        val p = MonoAudioProcessor(state)
        assertThat(p.configure(format(channels = 2)))
            .isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        state.update(PlaybackPrefs(monoAudio = true))
        // The previously-configured processor stays bypassed…
        assertThat(p.isActive).isFalse()
        // …until the sink reconfigures it (next track boundary / seek).
        p.reset()
        assertThat(p.configure(format(channels = 2)).channelCount).isEqualTo(1)
    }
}
