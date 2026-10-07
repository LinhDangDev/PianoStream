package com.pianostream.core.data.playback.model

/**
 * Durable queue cursor (`queue_state` row id=1): current index + position +
 * repeat/shuffle, written on the 5 s cadence and on lifecycle transitions (H6).
 * `currentIndex`/`positionMs` operate on **tracks** (CC1).
 */
public data class QueueState(
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    val updatedAt: Long,
)
