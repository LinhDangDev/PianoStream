package com.pianostream.app.fake

import com.pianostream.core.youtube.api.ExtractionException
import com.pianostream.core.youtube.api.ResolvedStream
import com.pianostream.core.youtube.api.StreamResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic `StreamResolver` (stub): resolves a canned stream; throws
 * `ExtractionException.NoFormats` once for [FAIL_ONCE_VIDEO_ID] so the H5
 * typed-error surface is reachable in the fake app.
 */
@Singleton
public class FakeStreamResolver @Inject constructor() : StreamResolver {

    private var failOnceUsed = false

    override suspend fun resolve(
        videoId: String,
        preferredItag: Int,
        force: Boolean,
    ): ResolvedStream {
        if (videoId == FAIL_ONCE_VIDEO_ID && !failOnceUsed) {
            failOnceUsed = true
            throw ExtractionException.NoFormats("no audio formats for $videoId")
        }
        return ResolvedStream(
            videoId = videoId,
            url = "https://fake.invalid/videoplayback?id=$videoId",
            itag = preferredItag,
            mimeType = "audio/webm",
            bitrate = 128_000,
            contentLength = 3_000_000,
            expiresAtEpochMs = Long.MAX_VALUE,
            expiresInSeconds = 21_600,
            headers = emptyMap(),
            clientId = "fake",
            resolvedAtUptimeMs = 0,
            expiresAtUptimeMs = Long.MAX_VALUE,
        )
    }

    public companion object {
        /** Resolve() throws NoFormats once for this videoId (H5 drill). */
        public const val FAIL_ONCE_VIDEO_ID: String = "ddddddddddd"
    }
}
