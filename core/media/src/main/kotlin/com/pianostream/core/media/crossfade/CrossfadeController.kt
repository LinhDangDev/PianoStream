package com.pianostream.core.media.crossfade

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pianostream.core.media.local.LocalMediaItemFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dual-player crossfade (H2/H3/L1): a secondary ExoPlayer ramps in the next
 * item over `crossfadeMs` while the primary ramps out.
 *
 * Eligibility is read synchronously from `MediaMetadata.extras` (stamped by
 * `LocalMediaItemFactory` — H2): fade between any two items whose `flacUri`
 * is known on both sides and differs — remote→remote, remote↔local and
 * disc→disc (L1). Never fade on a shared or unknown `flacUri`; never fade
 * between two `local` items whose windows haven't been fetched (H3). Remote
 * items have no `flacUri` — they fade against everything except another item
 * with the same unknown state? No — per spec, remote items carry no flacUri;
 * their fade key is the videoId (contentKey extra), never null.
 *
 * A fade is aborted — and the secondary released — on `isPlaying == false`
 * or `onPlayerError` mid-fade.
 */
class CrossfadeController(
    /** Builds/returns the lazily created secondary player (H4 config). */
    private val secondaryPlayer: () -> Player,
    private val primary: Player,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Sets the incoming track's ReplayGain on the secondary's own processor
     *  before `play()` (H3). */
    private val setSecondaryGain: (gainDb: Double?, peak: Double?) -> Unit,
    private val volumeRamp: VolumeRamp = VolumeRamp { p, v -> p.volume = v },
    private val crossfadeMsProvider: () -> Int,
) {

    /** True while a fade is ramping — exposed for the UI. */
    @Volatile
    var isCrossfading: Boolean = false
        private set

    private var fadeJob: kotlinx.coroutines.Job? = null

    /** Functional seam for volume writes (testable without a real player). */
    fun interface VolumeRamp {
        fun set(player: Player, volume: Float)
    }

    companion object {
        const val RAMP_STEP_MS = 50L

        /**
         * The fade key: `flacUri` for local items, `contentKey` (videoId) for
         * remote. `null` = unknown → never fades (H3).
         */
        fun fadeKeyOf(item: MediaItem?): String? {
            val extras = item?.mediaMetadata?.extras ?: return null
            return extras.getString(LocalMediaItemFactory.EXTRA_FLAC_URI)
                ?: extras.getString(LocalMediaItemFactory.EXTRA_CONTENT_KEY)
        }

        /** True when either item is local AND its window is unresolved (H3). */
        fun localWindowMissing(item: MediaItem?): Boolean {
            val id = item?.mediaId ?: return false
            if (!id.startsWith("pianostream://local/")) return false
            return item.mediaMetadata.extras
                ?.getString(LocalMediaItemFactory.EXTRA_FLAC_URI) == null
        }

        /**
         * L1 predicate: fade iff both fade keys are known, different, and no
         * side is an unresolved local item.
         */
        fun isEligible(from: MediaItem?, to: MediaItem?): Boolean {
            if (from == null || to == null) return false
            if (localWindowMissing(from) || localWindowMissing(to)) return false
            val a = fadeKeyOf(from) ?: return false
            val b = fadeKeyOf(to) ?: return false
            return a != b
        }
    }

    /**
     * Starts a fade into [next] over the configured `crossfadeMs` (0 = off →
     * no-op). The secondary is seeked to the item and its own ReplayGain
     * processor set before `play()` (H3).
     */
    fun crossfadeInto(
        next: MediaItem,
        gainDb: Double?,
        peak: Double?,
        positionMs: Long = 0L,
    ) {
        val duration = crossfadeMsProvider()
        if (duration <= 0 || !isEligible(primary.currentMediaItem, next)) return
        val secondary = secondaryPlayer()
        abort() // never overlap two fades
        isCrossfading = true
        setSecondaryGain(gainDb, peak)
        secondary.setMediaItem(next, positionMs)
        secondary.prepare()
        secondary.volume = 0f
        secondary.play()
        fadeJob = scope.launch {
            try {
                val steps = (duration / RAMP_STEP_MS).toInt().coerceAtLeast(1)
                repeat(steps) { i ->
                    if (!secondary.isPlaying) {
                        abortInternal(secondary) // isPlaying==false mid-fade → abort+release
                        return@launch
                    }
                    val t = (i + 1).toFloat() / steps
                    volumeRamp.set(secondary, t)
                    volumeRamp.set(primary, 1f - t)
                    delay(RAMP_STEP_MS)
                }
                primary.volume = 0f
                primary.pause()
                primary.volume = 1f // restore for the next natural transition
                isCrossfading = false
            } finally {
                // whatever happens, the flag must drop
                if (!secondary.isPlaying) abortInternal(secondary)
            }
        }
    }

    /** Abort + release the secondary (mid-fade stop or `onPlayerError`). */
    fun abort() {
        fadeJob?.cancel()
        fadeJob = null
        isCrossfading = false
        runCatching {
            secondaryPlayer().also {
                it.stop()
                it.volume = 1f
            }
        }
        runCatching { primary.volume = 1f }
    }

    private fun abortInternal(secondary: Player) {
        isCrossfading = false
        runCatching { secondary.stop() }
        runCatching { primary.volume = 1f }
    }

}
