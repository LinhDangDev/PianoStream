package com.pianostream.core.local.scan

import java.io.EOFException
import java.io.InputStream

/**
 * Reads just enough of a FLAC to recover its duration: `fLaC` marker +
 * the STREAMINFO metadata block (34 bytes). Never scans the body — the
 * scan's ≤1 MiB read budget stays intact (F20); an unreadable STREAMINFO
 * yields `null` (the caller skips the last track and reports, M3).
 */
object FlacHeaderReader {

    data class StreamInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
    ) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else totalSamples * 1000L / sampleRate
    }

    private fun readN(stream: InputStream, out: ByteArray): Boolean {
        var off = 0
        while (off < out.size) {
            val r = stream.read(out, off, out.size - off)
            if (r < 0) return off > 0
            off += r
        }
        return true
    }

    fun readStreamInfo(stream: InputStream): StreamInfo? = try {
        val magic = ByteArray(4)
        if (!readN(stream, magic) || magic.contentEquals("fLaC".toByteArray()).not()) {
            null
        } else {
            // Metadata blocks: 1-byte header (last-block flag + type), 3-byte length.
            readBlocks(stream)
        }
    } catch (e: Exception) {
        null
    }

    private fun readBlocks(stream: InputStream): StreamInfo? {
        while (true) {
            val hdr = ByteArray(4)
            if (!readN(stream, hdr)) return null
            val type = hdr[0].toInt() and 0x7f
            val len = (hdr[1].toInt() and 0xff shl 16) or
                (hdr[2].toInt() and 0xff shl 8) or (hdr[3].toInt() and 0xff)
            if (type == 0) { // STREAMINFO
                val si = ByteArray(len)
                if (!readN(stream, si) || len < 34) return null
                // bytes 10..17: sampleRate(20) | channels(3) | bps(5) | totalSamples(36)
                val b10 = si[10].toLong() and 0xff
                val b11 = si[11].toLong() and 0xff
                val b12 = si[12].toLong() and 0xff
                val b13 = si[13].toLong() and 0xff
                val b14 = si[14].toLong() and 0xff
                val b15 = si[15].toLong() and 0xff
                val b16 = si[16].toLong() and 0xff
                val b17 = si[17].toLong() and 0xff
                val packed = (b10 shl 56) or (b11 shl 48) or (b12 shl 40) or
                    (b13 shl 32) or (b14 shl 24) or (b15 shl 16) or
                    (b16 shl 8) or b17
                val sampleRate = (packed ushr 44).toInt()
                val channels = ((packed ushr 41) and 0x7).toInt() + 1
                val bitsPerSample = ((packed ushr 36) and 0x1f).toInt() + 1
                val totalSamples = packed and 0xfffffffffL
                return StreamInfo(sampleRate, channels, bitsPerSample, totalSamples)
            }
            // skip other blocks
            var left = len
            val buf = ByteArray(8192)
            while (left > 0) {
                val r = stream.read(buf, 0, minOf(buf.size, left))
                if (r < 0) throw EOFException()
                left -= r
            }
        }
    }
}
