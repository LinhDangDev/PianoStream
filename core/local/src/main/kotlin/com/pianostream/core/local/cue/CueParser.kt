package com.pianostream.core.local.cue

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Hand-rolled cue-sheet parser (~150 LOC, no library) — F20/CC7/CC11:
 *
 *  - hard caps: line ≤ 4 KiB, tracks/sheet ≤ 500, a sheet with zero valid
 *    `INDEX 01` ⇒ [CueParseException];
 *  - encoding: UTF-8 with BOM strip → CP1252 fallback on malformed input;
 *  - commands: `FILE TRACK INDEX PREGAP POSTGAP REM TITLE PERFORMER
 *    SONGWRITER ISRC FLAGS`; anything else ignored, never fatal;
 *  - `SONGWRITER` is a top-level **track** command (composer, CC7); `REM`
 *    keys parsed: REPLAYGAIN_{TRACK,ALBUM}_{GAIN,PEAK}, GENRE, DATE,
 *    DISCNUMBER, DISCID, TOTALDISCS — gains accept signed decimals ("-0.45 dB").
 */
object CueParser {

    const val MAX_LINE_BYTES = 4096
    const val MAX_TRACKS = 500
    const val PARSER_VERSION = 1

    fun parse(stream: InputStream, sourceLabel: String = "cue"): CueSheet =
        parse(stream.readBytes(), sourceLabel)

    fun parse(raw: ByteArray, sourceLabel: String = "cue"): CueSheet {
        val text = decode(raw)
        var fileName: String? = null
        var sheetTitle: String? = null
        var sheetPerformer: String? = null
        val sheetRem = RemAcc()
        val tracks = ArrayList<TrackAcc>()
        var cur: TrackAcc? = null
        var sawIndex01 = false

        for (rawLine in text.lines()) {
            if (rawLine.toByteArray(StandardCharsets.UTF_8).size > MAX_LINE_BYTES) {
                throw CueParseException("$sourceLabel: line > $MAX_LINE_BYTES bytes")
            }
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val head = line.substringBefore(' ').substringBefore('\t').uppercase()
            val rest = line.substring(head.length).trim()
            when (head) {
                "FILE" -> {
                    val parts = splitQuoted(rest)
                    fileName = parts.firstOrNull()?.unquote()
                        ?: throw CueParseException("$sourceLabel: FILE without target")
                }
                "TRACK" -> {
                    if (tracks.size + 1 >= MAX_TRACKS) {
                        throw CueParseException("$sourceLabel: > $MAX_TRACKS tracks")
                    }
                    val no = rest.substringBefore(' ').toIntOrNull()
                        ?: throw CueParseException("$sourceLabel: TRACK without number")
                    cur?.let { tracks += it }
                    cur = TrackAcc(trackNo = no, indexInCue = tracks.size)
                }
                "INDEX" -> {
                    val t = cur ?: continue
                    val parts = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (parts.size < 2) continue
                    val ms = CueTime.parse(parts[1]) ?: continue
                    when (parts[0]) {
                        "00" -> t.index00Ms = ms
                        "01" -> {
                            t.index01Ms = ms
                            sawIndex01 = true
                        }
                    }
                }
                "PREGAP" -> CueTime.parse(rest)?.let { cur?.pregapMs = it }
                "POSTGAP" -> CueTime.parse(rest)?.let { cur?.postgapMs = it }
                "REM" -> {
                    val key = rest.substringBefore(' ').uppercase()
                    val value = rest.substring(key.length).trim().unquote()
                    val target = cur?.rem ?: sheetRem
                    target.put(key, value)
                }
                "TITLE" -> if (cur != null) cur!!.title = rest.unquote() else sheetTitle = rest.unquote()
                "PERFORMER" -> if (cur != null) cur!!.performer = rest.unquote() else sheetPerformer = rest.unquote()
                "SONGWRITER" -> cur?.songwriter = rest.unquote()
                "ISRC" -> cur?.isrc = rest.trim()
                "FLAGS" -> cur?.flags = parseFlags(rest)
                else -> Unit // unknown commands are ignored, never fatal
            }
        }
        cur?.let { tracks += it }
        if (fileName == null) throw CueParseException("$sourceLabel: missing FILE")
        if (!sawIndex01 || tracks.none { it.index01Ms != null }) {
            throw CueParseException("$sourceLabel: no valid INDEX 01")
        }
        val done = tracks.mapNotNull { acc -> acc.index01Ms?.let { acc.build() } }
        return CueSheet(
            fileName = fileName!!,
            title = sheetTitle,
            performer = sheetPerformer,
            rem = sheetRem.build(),
            tracks = done,
        )
    }

    /** UTF-8 (BOM-stripped); CP1252 on malformed input — never fatal (F20). */
    internal fun decode(raw: ByteArray): String {
        var bytes = raw
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            bytes = bytes.copyOfRange(3, bytes.size)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun String.unquote(): String =
        trim().removeSurrounding("\"")

    private fun splitQuoted(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '"') {
                val end = s.indexOf('"', i + 1)
                if (end < 0) { out += s.substring(i); break }
                out += s.substring(i, end + 1)
                i = end + 1
            } else {
                val end = s.indexOf(' ', i).let { if (it < 0) s.length else it }
                out += s.substring(i, end)
                i = end
            }
            while (i < s.length && s[i] == ' ') i++
        }
        return out
    }

    private fun parseFlags(rest: String): Int {
        var f = 0
        for (tok in rest.split(Regex("\\s+"))) when (tok.uppercase()) {
            "DCP" -> f = f or 0x2
            "4CH" -> f = f or 0x4
            "PRE" -> f = f or 0x8
            "SCMS" -> f = f or 0x10
        }
        return f
    }

    private class RemAcc {
        var trackGainDb: Double? = null
        var trackPeak: Double? = null
        var albumGainDb: Double? = null
        var albumPeak: Double? = null
        var genre: String? = null
        var date: String? = null
        var discNumber: Int? = null
        var discId: String? = null
        var totalDiscs: Int? = null

        fun put(key: String, value: String) {
            val num = value.removeSuffix("dB").removeSuffix("db").trim()
            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGainDb = num.toDoubleOrNull()
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = num.toDoubleOrNull()
                "REPLAYGAIN_ALBUM_GAIN" -> albumGainDb = num.toDoubleOrNull()
                "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = num.toDoubleOrNull()
                "GENRE" -> genre = value
                "DATE" -> date = value
                "DISCNUMBER" -> discNumber = num.toIntOrNull()
                "DISCID" -> discId = value
                "TOTALDISCS" -> totalDiscs = num.toIntOrNull()
            }
        }

        fun build() = CueRemFields(
            trackGainDb, trackPeak, albumGainDb, albumPeak,
            genre, date, discNumber, discId, totalDiscs,
        )
    }

    private class TrackAcc(
        val trackNo: Int,
        val indexInCue: Int,
        var title: String? = null,
        var performer: String? = null,
        var songwriter: String? = null,
        var index00Ms: Long? = null,
        var index01Ms: Long? = null,
        var pregapMs: Long? = null,
        var postgapMs: Long? = null,
        var isrc: String? = null,
        var flags: Int = 0,
        val rem: RemAcc = RemAcc(),
    ) {
        fun build() = CueTrack(
            indexInCue = indexInCue, trackNo = trackNo, title = title,
            performer = performer, songwriter = songwriter,
            index00Ms = index00Ms, index01Ms = index01Ms!!,
            pregapMs = pregapMs, postgapMs = postgapMs,
            trackGainDb = rem.trackGainDb, trackPeak = rem.trackPeak,
            isrc = isrc, flags = flags,
        )
    }
}
