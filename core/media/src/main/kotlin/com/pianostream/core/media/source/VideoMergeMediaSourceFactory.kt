package com.pianostream.core.media.source

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.pianostream.core.data.uri.PianoStreamUri

/**
 * `MediaSource.Factory` wrapper that turns `pianostream://video/<videoId>`
 * items into a [MergingMediaSource]: the item's own URI resolves (through
 * `StreamResolvingDataSource`) to a video-only adaptive itag, and a
 * synthesized `pianostream://stream/<videoId>` child resolves to the usual
 * audio itag — the same NewPipe-style split YouTube's own clients use.
 *
 * Muxed formats never reach the video child (the video allow-list is
 * adaptive-only), so the merge can never double audio; a client response
 * without adaptive formats simply surfaces `NoFormats` on the video child,
 * like any other failed resolve. `clipDurations` absorbs the ±ms duration
 * mismatch between the two children (each child period is clipped to the
 * shared shortest duration). Every other URI delegates unchanged.
 */
class VideoMergeMediaSourceFactory(
    private val delegate: MediaSource.Factory,
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val parsed = mediaItem.localConfiguration?.uri
            ?.toString()
            ?.let(PianoStreamUri::parse)
        if (parsed !is PianoStreamUri.Id.Video) {
            return delegate.createMediaSource(mediaItem)
        }
        val streamUri = PianoStreamUri.stream(parsed.videoId)
        val audioItem = mediaItem.buildUpon()
            .setMediaId(streamUri)
            .setUri(streamUri)
            .build()
        return MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ true,
            /* clipDurations = */ true,
            delegate.createMediaSource(mediaItem),
            delegate.createMediaSource(audioItem),
        )
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    @Deprecated("delegated to [delegate]; kept for interface conformance")
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    @Deprecated("delegated to [delegate]; kept for interface conformance")
    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }
}
