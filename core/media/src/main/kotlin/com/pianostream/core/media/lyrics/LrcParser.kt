package com.pianostream.core.media.lyrics

/**
 * `.lrc` parser — pure Kotlin (no Android imports) so it runs in plain JUnit.
 *
 *  - Timestamps `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]` — the
 *    fraction is scaled to ms (`.5` = 500 ms, `.05` = 50 ms, `.005` = 5 ms).
 *  - A leading run of time tags yields one [LyricsLine] per tag
 *    (multi-stamped lines like `[0:10.00][0:20.00]oh` are cues that repeat).
 *  - `[offset:+-ms]` is a global shift applied to every timestamp wherever
 *    the tag appears (positive pushes lyrics later); stamps clamp at 0.
 *  - Other bracket metadata (`[ti:]`, `[ar:]`, `[length:]`, `[re:]`, `[ve:]`,
 *    malformed `[...]` groups) is skipped; blank lyric text is kept as `""`
 *    (instrumental markers) and untagged prose lines are ignored.
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG = Regex("""\[offset\s*:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val LEADING_TAG = Regex("""^\[[^\]]*]""")

    /** Ordered timed lines (sorted by [LyricsLine.atMs]); empty for untagged text. */
    fun parse(text: String): List<LyricsLine> {
        val offsetMs = globalOffsetMs(text)
        val out = ArrayList<LyricsLine>()
        for (rawLine in text.removePrefix("\uFEFF").lines()) {
            var rest = rawLine.trim()
            if (rest.isEmpty()) continue

            // Consume the leading run of [...] tags; time tags collect stamps,
            // everything else (metadata, malformed groups) is ignored.
            val stamps = ArrayList<Long>()
            while (true) {
                val tag = LEADING_TAG.find(rest) ?: break
                TIME_TAG.matchEntire(tag.value)?.let { m -> stamps += m.toTimestampMs() }
                rest = rest.substring(tag.range.last + 1)
            }
            if (stamps.isEmpty()) continue

            val lyricText = rest.trim()
            for (stamp in stamps) {
                out += LyricsLine((stamp + offsetMs).coerceAtLeast(0L), lyricText)
            }
        }
        return out.sortedBy { it.atMs }
    }

    /**
     * Classifies raw lyric payload: ≥1 timed line → [Lyrics.Synced];
     * otherwise the tag-stripped text → [Lyrics.Plain]; blank → null.
     */
    fun toLyrics(text: String): Lyrics? {
        val lines = parse(text)
        if (lines.isNotEmpty()) return Lyrics.Synced(lines)
        val plain = stripTagLines(text)
        return if (plain.isBlank()) null else Lyrics.Plain(plain)
    }

    private fun globalOffsetMs(text: String): Long {
        var offsetMs = 0L
        for (line in text.lines()) {
            OFFSET_TAG.find(line.trim())?.let { m ->
                offsetMs = m.groupValues[1].toLongOrNull() ?: 0L
            }
        }
        return offsetMs
    }

    private fun MatchResult.toTimestampMs(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fraction = groupValues[3]
        val fractionMs = if (fraction.isEmpty()) 0L
        else (fraction.toLongOrNull() ?: 0L) * pow10(3 - fraction.length)
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    private fun pow10(n: Int): Long = when (n) {
        0 -> 1L
        1 -> 10L
        2 -> 100L
        else -> 1_000L
    }

    /** Drops lines whose content is only `[...]` tags, keeps prose, trims. */
    private fun stripTagLines(text: String): String =
        text.removePrefix("\uFEFF").lines()
            .filter { line ->
                var rest = line.trim()
                while (true) {
                    val tag = LEADING_TAG.find(rest) ?: break
                    rest = rest.substring(tag.range.last + 1).trim()
                }
                rest.isNotEmpty()
            }
            .joinToString("\n")
            .trim()
}
