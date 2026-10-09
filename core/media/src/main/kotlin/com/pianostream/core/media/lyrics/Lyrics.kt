package com.pianostream.core.media.lyrics

data class LyricsLine(val atMs: Long, val text: String)

sealed interface Lyrics {
    data class Synced(val lines: List<LyricsLine>) : Lyrics
    data class Plain(val text: String) : Lyrics
}

data class LyricsQuery(val mediaId: String, val title: String, val artist: String, val durationMs: Long, val localFileUri: String?)

interface LyricsRepository { suspend fun get(query: LyricsQuery): Lyrics? }
