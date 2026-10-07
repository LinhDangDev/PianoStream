package com.pianostream.core.media.audio

import kotlin.math.min
import kotlin.math.pow

/**
 * Pure ReplayGain math (H3): `gain_linear = 10^(dB/20)`, scaled by the
 * preamp, peak-limited by `min(1.0, 1.0/peak)` when `peak > 1`.
 */
object GainCalculator {

    /**
     * @param gainDb per-track ReplayGain dB (null ⇒ unity).
     * @param peak   per-track peak (0..1); `> 1` values are treated literally —
     *               values > 1 attenuate, null skips the limiter.
     * @param preampDb user preamp setting, applied on top of the track gain.
     */
    fun linearGain(gainDb: Double?, peak: Double?, preampDb: Double): Float {
        val base = 10.0.pow(((gainDb ?: 0.0) + preampDb) / 20.0)
        val limiter = if (peak != null && peak > 1.0) 1.0 / peak else 1.0
        return (base * min(1.0, limiter)).toFloat()
    }
}
