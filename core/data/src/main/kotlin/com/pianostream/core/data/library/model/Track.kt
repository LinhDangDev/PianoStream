package com.pianostream.core.data.library.model

/**
 * One cue-derived track (`tracks` row, frozen schema v1;
 * `UNIQUE(discId, trackNo)`).
 *
 * Windows **exclude** pregap (CC11): `startMs`/`endMs` are INDEX 01 bounds and
 * `endMs > startMs && endMs <= flacDurationMs` is required at write time.
 * `pregapStartMs` is the INDEX 00 offset when present — track 1's INDEX 00
 * lead-in is defined (it is part of track 1 or lands in `pregapStartMs`).
 * `composer` comes from the track's top-level `SONGWRITER` (CC7).
 * `gainDb`/`peak` are per-track ReplayGain from the cue sheet (or null when
 * absent) and feed the ReplayGainAudioProcessor via MediaMetadata extras (H3).
 */
public data class Track(
    val id: Long,
    val discId: Long,
    val trackNo: Int,
    val title: String,
    val performer: String?,
    val composer: String?,
    val startMs: Long,
    val endMs: Long?,
    val pregapStartMs: Long?,
    val gainDb: Double?,
    val peak: Double?,
    val isrc: String?,
    val flags: Int,
)
