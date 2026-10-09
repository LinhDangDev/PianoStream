package com.pianostream.core.media

/**
 * Pinned audio-quality tiers for YouTube stream playback (Settings → Audio
 * quality). [itag] is the preferred audio itag handed to the stream resolver;
 * `null` keeps the canonical picker order (auto).
 *
 * LOW → `250` Opus ~48 kbps, NORMAL → `140` M4A ~128 kbps,
 * HIGH → `251` Opus ~160 kbps.
 */
enum class AudioQualityTier(val itag: Int?) {
    AUTO(null),
    LOW(250),
    NORMAL(140),
    HIGH(251),
}
