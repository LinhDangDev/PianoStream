package com.pianostream.core.local.cue

/**
 * `mm:ss:ff` cue time → milliseconds (75 frames/second).
 * Bounds: `mm <= 99`, `ss <= 59`, `ff <= 74` — anything outside is invalid
 * (CC11), and invalid `INDEX` lines make the sheet fail (zero valid
 * `INDEX 01` ⇒ [CueParseException]).
 */
object CueTime {

    private val PATTERN = Regex("""(\d+):(\d+):(\d+)""")

    /** Returns milliseconds or `null` for malformed/out-of-range input. */
    fun parse(value: String): Long? {
        val m = PATTERN.matchEntire(value.trim()) ?: return null
        val mm = m.groupValues[1].toIntOrNull() ?: return null
        val ss = m.groupValues[2].toIntOrNull() ?: return null
        val ff = m.groupValues[3].toIntOrNull() ?: return null
        if (mm > 99 || ss > 59 || ff > 74) return null
        return (mm * 60L + ss) * 1000L + ff * 1000L / 75L
    }
}
