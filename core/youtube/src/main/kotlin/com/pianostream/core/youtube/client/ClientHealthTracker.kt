package com.pianostream.core.youtube.client

import android.os.SystemClock

/**
 * Per-client consecutive-failure tracking with demotion and time-based
 * recovery (H1). All state is in-memory only.
 *
 *  - [recordFailure]: +1 consecutive failure; at [DEMOTE_AFTER] the client is
 *    demoted for [cooldownMs].
 *  - [recordSuccess]: resets the counter immediately.
 *  - [isHealthy]: a demoted client reports unhealthy until its cooldown
 *    elapses, then re-enters the rotation (failures keep counting).
 */
internal class ClientHealthTracker(
    private val uptimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    private data class State(
        var consecutiveFailures: Int = 0,
        var demotedUntilUptimeMs: Long = 0L,
    )

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun recordFailure(clientId: String) {
        val s = states.getOrPut(clientId) { State() }
        s.consecutiveFailures++
        if (s.consecutiveFailures >= DEMOTE_AFTER) {
            s.demotedUntilUptimeMs = uptimeMs() + cooldownMs
        }
    }

    @Synchronized
    fun recordSuccess(clientId: String) {
        states.getOrPut(clientId) { State() }.consecutiveFailures = 0
    }

    @Synchronized
    fun isHealthy(clientId: String): Boolean {
        val s = states[clientId] ?: return true
        return uptimeMs() >= s.demotedUntilUptimeMs
    }

    @Synchronized
    fun consecutiveFailures(clientId: String): Int =
        states[clientId]?.consecutiveFailures ?: 0

    /** Test/introspection hook: when the client exits cooldown (uptime ms). */
    @Synchronized
    fun demotedUntil(clientId: String): Long = states[clientId]?.demotedUntilUptimeMs ?: 0L

    @Synchronized
    fun clear() = states.clear()

    companion object {
        const val DEMOTE_AFTER = 3
        const val DEFAULT_COOLDOWN_MS = 15 * 60 * 1000L
    }
}
