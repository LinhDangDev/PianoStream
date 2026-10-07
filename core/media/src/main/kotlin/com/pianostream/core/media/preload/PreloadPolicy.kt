package com.pianostream.core.media.preload

import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

/**
 * M5 — the single `DefaultPreloadManager`'s target control: the item
 * ±1 around the currently playing index gets
 * `PreloadStatus.specifiedRangeLoaded(3000)`; everything else is off.
 *
 * `getTargetPreloadStatus` receives the ABSOLUTE media index — the control
 * keeps the playing index via [onUsedByPlayer], updated by the service on
 * `onMediaItemTransition` (same contract as the upstream docs example).
 */
class PreloadPolicy(
    @Volatile var currentPlayingIndex: Int = 0,
) : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    override fun getTargetPreloadStatus(
        index: Int,
    ): DefaultPreloadManager.PreloadStatus =
        if (abs(index - currentPlayingIndex) == 1) {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_MS)
        } else {
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }

    /** Called by the service when the playing index changes. */
    fun onUsedByPlayer(index: Int) {
        currentPlayingIndex = index
    }

    companion object {
        const val PRELOAD_MS = 3_000L
    }
}
