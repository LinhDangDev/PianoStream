package com.pianostream.core.media.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.AudioStreamResolver
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `audioItagProvider` seam — the audio mirror of `videoItagProvider`: the
 * tier itag (`null` = auto) is read per open and pushed through
 * [AudioStreamResolver.resolveAudio] when the resolver supports it, else
 * through [StreamResolver.resolve] with the tier itag as `preferredItag`.
 */
@RunWith(RobolectricTestRunner::class)
class StreamResolvingDataSourceAudioItagTest {

    private open class RecordingResolver : StreamResolver {
        data class Call(val videoId: String, val itag: Int?, val force: Boolean)

        val calls = mutableListOf<Call>()
        var resolvedItag: Int = 251

        protected fun stream(call: Call): ResolvedStream = ResolvedStream(
            videoId = call.videoId,
            url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=999",
            itag = resolvedItag,
            mimeType = "audio/webm",
            bitrate = 130_000,
            contentLength = 3_000_000,
            expiresAtEpochMs = 9_999_999L,
            expiresInSeconds = 3_600,
            headers = emptyMap(),
            clientId = "ANDROID_VR",
            resolvedAtUptimeMs = 0,
            expiresAtUptimeMs = Long.MAX_VALUE,
        )

        override suspend fun resolve(
            videoId: String,
            preferredItag: Int,
            force: Boolean,
        ): ResolvedStream {
            val call = Call(videoId, preferredItag, force)
            calls += call
            return stream(call)
        }
    }

    private class AudioRecordingResolver :
        RecordingResolver(), AudioStreamResolver {

        val audioCalls = mutableListOf<Call>()

        override suspend fun resolveAudio(
            videoId: String,
            preferredItag: Int?,
            force: Boolean,
        ): ResolvedStream {
            val call = Call(videoId, preferredItag, force)
            audioCalls += call
            return stream(call)
        }
    }

    private class RecordingUpstream : DataSource {
        val openedSpecs = mutableListOf<DataSpec>()

        override fun open(dataSpec: DataSpec): Long {
            openedSpecs += dataSpec
            return 1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri() = openedSpecs.lastOrNull()?.uri
        override fun close() {}
        override fun addTransferListener(t: TransferListener) {}
    }

    private lateinit var upstream: RecordingUpstream

    private fun source(
        resolver: StreamResolver,
        audioItag: () -> Int?,
    ) = StreamResolvingDataSource(
        resolver = resolver,
        upstream = upstream,
        preferredItag = 251,
        localResolver = { null },
        audioItagProvider = audioItag,
    )

    @Before
    fun setUp() {
        upstream = RecordingUpstream()
    }

    private fun specOf(uri: String) = DataSpec.Builder().setUri(uri).build()

    @Test
    fun stream_uri_passes_tier_itag_through_resolveAudio() {
        val resolver = AudioRecordingResolver()
        source(resolver) { 250 }.open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(resolver.audioCalls).hasSize(1)
        assertThat(resolver.audioCalls[0].itag).isEqualTo(250)
        assertThat(resolver.audioCalls[0].force).isFalse()
        assertThat(resolver.calls).isEmpty()
        assertThat(upstream.openedSpecs.single().uri.toString())
            .startsWith("https://rr1---sn-abc.googlevideo.com/")
    }

    @Test
    fun stream_uri_null_tier_keeps_auto_through_resolveAudio() {
        val resolver = AudioRecordingResolver()
        source(resolver) { null }.open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(resolver.audioCalls).hasSize(1)
        assertThat(resolver.audioCalls[0].itag).isNull()
        assertThat(resolver.calls).isEmpty()
    }

    @Test
    fun plain_resolver_falls_back_to_resolve_with_tier_itag() {
        val resolver = RecordingResolver()
        source(resolver) { 140 }.open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(resolver.calls).hasSize(1)
        assertThat(resolver.calls[0].itag).isEqualTo(140)
    }

    @Test
    fun provider_is_read_per_open_so_tier_switches_apply() {
        val resolver = AudioRecordingResolver()
        var tier: Int? = null
        val source = source(resolver) { tier }
        source.open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        tier = 250
        source.open(specOf("pianostream://stream/dQw4w9WgXcQ"))
        assertThat(resolver.audioCalls.map { it.itag })
            .containsExactly(null, 250)
            .inOrder()
    }
}
