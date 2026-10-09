package com.pianostream.core.youtube.spotify

import com.pianostream.core.youtube.api.SearchFilter
import com.pianostream.core.youtube.api.YouTubeClient
import com.pianostream.core.youtube.api.YouTubeItem
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Maps imported Spotify tracks onto YouTube Music search results.
 *
 * Per track: `search("<artist> <title>", SONGS)`, then pick the best
 * `videoId`-carrying item — TRACK ("song") results first, VIDEOs only when no
 * song result exists; within a pool the first item whose `durationMs` is
 * within [DURATION_TOLERANCE] of the Spotify duration wins (YouTube's result
 * order is already relevance-ranked; the duration check only vetoes clear
 * outliers like covers/sped-ups). Falls back to the pool's first item when
 * nothing is inside the tolerance or durations are unknown.
 *
 * [run] matches with bounded parallelism ([parallelism] concurrent searches)
 * under structured concurrency — cancelling the calling scope stops the
 * whole import between tracks.
 */
public class SpotifyImportRunner(
    private val youTubeClient: YouTubeClient,
    private val parallelism: Int = 4,
) {

    /** Outcome for one imported track. */
    public sealed interface Match {
        /** A usable `pianostream://stream/`-able YouTube item. */
        public data class Matched(val item: YouTubeItem) : Match

        /** Search ran; nothing playable came back. */
        public data object NotFound : Match

        /** The search call itself failed (network/rate-limit/…). */
        public data class Failed(val error: Throwable) : Match
    }

    /** Per-track progress callback (index + outcome). */
    public fun interface OnItem {
        public fun onItem(index: Int, result: Match)
    }

    /**
     * Matches every track, invoking [onItem] as each settles. Returns the
     * full result list aligned with [tracks] (index → [Match]).
     */
    public suspend fun run(
        tracks: List<SpotifyPlaylistImporter.ImportedTrack>,
        onItem: OnItem = OnItem { _, _ -> },
    ): List<Match> = coroutineScope {
        val sem = Semaphore(parallelism.coerceAtLeast(1))
        tracks.mapIndexed { i, track ->
            async {
                val result = sem.withPermit { matchOne(track) }
                onItem.onItem(i, result)
                result
            }
        }.map { it.await() }
    }

    /** One track → best YouTube candidate (or [Match.NotFound]/[Match.Failed]). */
    public suspend fun matchOne(
        track: SpotifyPlaylistImporter.ImportedTrack,
    ): Match {
        val query = buildQuery(track)
        val page = try {
            youTubeClient.search(query, SearchFilter.SONGS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Match.Failed(e)
        }
        val candidates = page.sections
            .flatMap { it.items }
            .filter { it.videoId != null }
        if (candidates.isEmpty()) return Match.NotFound

        val tracksPool = candidates.filter { it.kind == YouTubeItem.Kind.TRACK }
        val pool = tracksPool.ifEmpty {
            candidates.filter { it.kind == YouTubeItem.Kind.VIDEO }
        }
        if (pool.isEmpty()) return Match.NotFound

        val expected = track.durationMs
        val chosen = if (expected != null && expected > 0L) {
            pool.firstOrNull { withinTolerance(it.durationMs, expected) }
                ?: pool.first()
        } else {
            pool.first()
        }
        return Match.Matched(chosen)
    }

    private fun withinTolerance(candidateMs: Long?, expectedMs: Long): Boolean {
        if (candidateMs == null) return false
        return abs(candidateMs - expectedMs) <= expectedMs * DURATION_TOLERANCE
    }

    internal fun buildQuery(track: SpotifyPlaylistImporter.ImportedTrack): String {
        // First artist only — collab lists ("A, B, C") just add noise to the
        // song query; the full list still sits on the imported row.
        val artist = track.artist?.substringBefore(',')?.trim().orEmpty()
        return listOf(artist, track.title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private companion object {
        /** Duration gate: |yt - spotify| ≤ 15% of the Spotify duration. */
        const val DURATION_TOLERANCE = 0.15
    }
}
