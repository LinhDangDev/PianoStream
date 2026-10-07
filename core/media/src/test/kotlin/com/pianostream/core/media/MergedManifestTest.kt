package com.pianostream.core.media

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Asserts the merged `:core:media` manifest carries the service contract
 * (Phase 2 success criterion): FGS permission, `mediaPlayback` type,
 * `exported=true`, and both `MediaSessionService`/`MediaBrowserService`
 * intent-filters.
 */
class MergedManifestTest {

    private fun mergedManifest(): File {
        // Unit tests run with CWD = module dir.
        val candidates = listOf(
            "build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifest/debug/AndroidManifest.xml",
        ).map(::File)
        return candidates.firstOrNull { it.exists() }
            ?: error("merged manifest not found; run processDebugManifest first")
    }

    @Test
    fun fgs_permission_and_type_present() {
        val xml = mergedManifest().readText()
        assertThat(xml).contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        assertThat(xml).contains("android:foregroundServiceType=\"mediaPlayback\"")
        assertThat(xml).contains(".PlaybackService")
        assertThat(xml).contains("android:exported=\"true\"")
    }

    @Test
    fun both_intent_filters_present() {
        val xml = mergedManifest().readText()
        assertThat(xml).contains("androidx.media3.session.MediaSessionService")
        assertThat(xml).contains("android.media.browse.MediaBrowserService")
    }
}
