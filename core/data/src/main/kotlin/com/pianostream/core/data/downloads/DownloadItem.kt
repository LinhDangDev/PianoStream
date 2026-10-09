package com.pianostream.core.data.downloads

import com.pianostream.core.data.uri.PianoStreamUri

/** Lifecycle of one offline download record. */
public enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

/**
 * One offline audio download — a `pianostream://stream/<videoId>` item whose
 * bytes live at `filesDir/downloads/<videoId>.bin`. Display columns are
 * denormalized per CC6 so the Downloads tab renders without joins.
 *
 * `bytesDownloaded` tracks live write progress while DOWNLOADING and the
 * final file size once DONE (so Settings → Storage can sum real bytes on
 * disk). `error` is set only for FAILED.
 */
public data class DownloadItem(
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val artworkUri: String? = null,
    val filePath: String? = null,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    /** The `pianostream://stream/<videoId>` id the queue/player uses. */
    public val mediaId: String get() = PianoStreamUri.stream(videoId)

    /** 0f..1f while the content length is known; null when indeterminate. */
    public val progressFraction: Float? get() =
        if (bytesTotal > 0L) {
            (bytesDownloaded.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    public val isDone: Boolean get() = state == DownloadState.DONE
}
