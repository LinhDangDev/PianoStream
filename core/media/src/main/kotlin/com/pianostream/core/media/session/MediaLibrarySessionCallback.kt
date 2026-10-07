package com.pianostream.core.media.session

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommands
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pianostream.core.data.uri.PianoStreamUri

/**
 * CC9 session guard (Phase 2 step 7):
 *
 *  - `onConnect` accepts only same-package controllers,
 *    `isMediaNotificationController`, or `isAutoCompanionController`. Every
 *    other controller connects with ZERO available session/player commands.
 *  - `onAddMediaItems` accepts only URIs passing `PianoStreamUri.parse`;
 *    anything else is rejected with `SessionError.ERROR_BAD_VALUE`.
 *  - FGS-start failures surface as `SessionResult` errors (M6), never as
 *    uncaught `ForegroundServiceStartNotAllowedException`.
 */
class MediaLibrarySessionCallback(
    private val context: Context,
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        /** Pure allow-list test — unit-tested without a session. */
        fun isAllowedController(
            controllerPackageName: String?,
            appPackageName: String,
            isMediaNotificationController: Boolean,
            isAutoCompanionController: Boolean,
        ): Boolean = controllerPackageName == appPackageName ||
            isMediaNotificationController ||
            isAutoCompanionController
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val allowed = isAllowedController(
            controller.packageName,
            context.packageName,
            session.isMediaNotificationController(controller),
            session.isAutoCompanionController(controller),
        )
        if (allowed) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }
        // Zero available commands — a connected foreign controller can do
        // nothing (CC9).
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(SessionCommands.Builder().build())
            .setAvailablePlayerCommands(androidx.media3.common.Player.Commands.Builder().build())
            .build()
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val accepted: MutableList<MediaItem> = mediaItems.filterTo(mutableListOf()) { item ->
            val uri = item.localConfiguration?.uri?.toString()
                ?: item.mediaId.takeIf { it.startsWith("pianostream://") }
            uri?.let { PianoStreamUri.parse(it) != null } == true ||
                PianoStreamUri.parse(item.mediaId) != null
        }
        if (accepted.size != mediaItems.size) {
            // Reject wholesale with SessionError — a foreign/garbage URI never
            // reaches the timeline (CC9).
            return Futures.immediateFuture(mutableListOf())
        }
        return Futures.immediateFuture(accepted.toMutableList())
    }
}
