package com.pianostream.core.youtube.client

/**
 * Ordered client iteration honoring the catalog's lifecycle/transport flags:
 *
 *  - `Lifecycle.BROKEN` clients never participate.
 *  - `Transport.SABR`-only clients are skipped — SABR playback is out of
 *    scope (R1).
 *  - `requiresAuth` clients participate only when `signedIn` (no auth in this
 *    build → WEB_REMIX is skipped by default).
 *  - A demoted client ([ClientHealthTracker.isHealthy] = false) is skipped
 *    until its cooldown elapses.
 *  - [excluding] removes a client for one resolve cycle — used by the
 *    H1 force-resolve path after a cipher/PoToken failure.
 */
internal class ClientFallbackStrategy(
    private val health: ClientHealthTracker,
    private val catalog: List<YouTubeClientCatalog.Client> = YouTubeClientCatalog.ordered,
) {

    fun orderedCandidates(
        signedIn: Boolean = false,
        excluding: String? = null,
    ): List<YouTubeClientCatalog.Client> =
        catalog.asSequence()
            .filter { it.lifecycle != YouTubeClientCatalog.Lifecycle.BROKEN }
            .filter { it.transport != YouTubeClientCatalog.Transport.SABR }
            .filter { !it.requiresAuth || signedIn }
            .filter { it.id != excluding }
            .filter { health.isHealthy(it.id) }
            .toList()

    /** All clients except `excluding`, ignoring health — used for diagnostics
     *  and the fail-closed path that must report "every client failed". */
    fun allExcept(excluding: String? = null): List<YouTubeClientCatalog.Client> =
        catalog.filter { it.id != excluding }
}
