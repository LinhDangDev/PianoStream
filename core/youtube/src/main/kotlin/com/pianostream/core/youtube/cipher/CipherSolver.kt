package com.pianostream.core.youtube.cipher

/**
 * Solves `sig`/`n` ciphers by evaluating EJS-preprocessed player code.
 *
 * F14 contract shared by every implementation (QuickJS and the WebView
 * fallback): ~16 MB memory ceiling, a ~250 ms hard deadline that aborts the
 * eval into [com.pianostream.core.youtube.api.ExtractionException.CipherFailure],
 * and execution on a dedicated dispatcher — never the ExoPlayer loading
 * thread — so a hung script cannot stall playback.
 */
internal interface CipherSolver {
    suspend fun solve(request: SolveRequest): SolvedCipher
}
