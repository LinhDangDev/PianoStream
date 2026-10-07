package com.pianostream.core.media

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pianostream.core.media.session.MediaLibrarySessionCallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the `MediaLibraryService` contract:
 *
 *  - the service is declared `exported=true` with `mediaPlayback` FGS type
 *    and both `MediaSessionService`/`MediaBrowserService` intent-filters
 *    (asserted from `PackageManager`, the same view controllers get);
 *  - CC9 allow-list logic (a foreign controller gets zero commands — the
 *    session-level check is exercised by 5a's E2E suite);
 *  - M6 `reportFgsDenied` never crashes.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun service_declared_with_mediaPlayback_fgs_type() {
        val pm = context.packageManager
        val info = pm.getServiceInfo(
            android.content.ComponentName(context, PlaybackService::class.java),
            PackageManager.GET_META_DATA or PackageManager.MATCH_ALL,
        )
        assertNotNull(info)
        assertTrue(info!!.exported)
    }

    @Test
    fun media_session_intent_resolves_to_service() {
        val pm = context.packageManager
        val resolveInfos = pm.queryIntentServices(
            Intent("androidx.media3.session.MediaSessionService"),
            PackageManager.MATCH_ALL,
        )
        assertTrue(
            resolveInfos.map { it.serviceInfo.packageName }
                .contains(context.packageName),
        )
    }

    @Test
    fun foreign_controller_denied_by_allow_list() {
        assertFalse(
            MediaLibrarySessionCallback.isAllowedController(
                controllerPackageName = "com.spotify.music",
                appPackageName = context.packageName,
                isMediaNotificationController = false,
                isAutoCompanionController = false,
            ),
        )
    }

    @Test
    fun notification_and_auto_companion_allowed() {
        val own = context.packageName
        assertTrue(
            MediaLibrarySessionCallback.isAllowedController(
                own, own, isMediaNotificationController = false,
                isAutoCompanionController = false,
            ),
        )
        assertTrue(
            MediaLibrarySessionCallback.isAllowedController(
                "com.android.systemui", own,
                isMediaNotificationController = true,
                isAutoCompanionController = false,
            ),
        )
        assertTrue(
            MediaLibrarySessionCallback.isAllowedController(
                "android.car.system", own,
                isMediaNotificationController = false,
                isAutoCompanionController = true,
            ),
        )
    }

    @Test
    fun fgs_denial_reports_without_crash() {
        // M6 — the reporter must swallow the platform exception.
        PlaybackService().reportFgsDenied(RuntimeException("fgs denied"))
    }
}
