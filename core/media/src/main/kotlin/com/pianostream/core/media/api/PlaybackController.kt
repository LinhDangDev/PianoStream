package com.pianostream.core.media.api

import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow

/**
 * The app-facing handle to `PlaybackService` (Interface Freeze; Phase 2
 * implements `PlaybackControllerImpl`).
 *
 * Callers drive playback through [player] — a `MediaController` bound to the
 * service session once [connect] has succeeded. All queue operations are
 * per-track `MediaItem` commands (CC1).
 *
 *  - **CC9** — the backing `MediaLibraryService` accepts only same-package
 *    controllers, `isMediaNotificationController`, or
 *    `isAutoCompanionController`; `onAddMediaItems` accepts only validated
 *    `pianostream://` URIs (local URIs resolve through `discs`/`tracks` rows —
 *    the URI's own path is never trusted).
 *  - **M9** — cold-start queue+cursor restore happens inside
 *    `connect()`/the session's `onConnect` path, not `onPlaybackResumption`.
 *  - **C4** — `taskRemovalPolicy(STOP|PAUSE)` is consumed by the service's
 *    `onTaskRemoved`.
 */
public interface PlaybackController {

    /** The connected `MediaController` (as a `Player`). Usable after [connect]. */
    public val player: Player

    /**
     * Connects to the playback session, restoring the durable queue + cursor on
     * cold start (M9). Suspends until the controller is usable; returns `false`
     * when the session refused the connection (CC9) or binding failed.
     */
    public suspend fun connect(): Boolean

    /** Emits `true` once the restored queue is playable; `false` before/empty. */
    public val isPlaylistReady: Flow<Boolean>

    /** Disconnects the controller. Idempotent. */
    public fun release()
}
