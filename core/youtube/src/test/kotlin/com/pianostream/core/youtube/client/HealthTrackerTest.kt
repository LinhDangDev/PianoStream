package com.pianostream.core.youtube.client

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** H1: 3 consecutive failures demote; cooldown recovery; success resets. */
class HealthTrackerTest {

    private var now = 0L
    private val tracker = ClientHealthTracker(uptimeMs = { now }, cooldownMs = 60_000)

    @Test
    fun `unknown client is healthy`() {
        assertThat(tracker.isHealthy("NEW")).isTrue()
    }

    @Test
    fun `three consecutive failures demote`() {
        repeat(3) { tracker.recordFailure("VISIONOS") }
        assertThat(tracker.isHealthy("VISIONOS")).isFalse()
        assertThat(tracker.consecutiveFailures("VISIONOS")).isEqualTo(3)
    }

    @Test
    fun `two failures keep client healthy`() {
        repeat(2) { tracker.recordFailure("X") }
        assertThat(tracker.isHealthy("X")).isTrue()
    }

    @Test
    fun `cooldown expiry restores health`() {
        repeat(3) { tracker.recordFailure("VISIONOS") }
        now += 60_000
        assertThat(tracker.isHealthy("VISIONOS")).isTrue()
    }

    @Test
    fun `success resets the counter`() {
        repeat(2) { tracker.recordFailure("VISIONOS") }
        tracker.recordSuccess("VISIONOS")
        tracker.recordFailure("VISIONOS")
        tracker.recordFailure("VISIONOS")
        // 1 + reset + 2 = counter at 2, still healthy
        assertThat(tracker.isHealthy("VISIONOS")).isTrue()
    }

    @Test
    fun `demotion is per client`() {
        repeat(3) { tracker.recordFailure("A") }
        assertThat(tracker.isHealthy("A")).isFalse()
        assertThat(tracker.isHealthy("B")).isTrue()
    }
}
