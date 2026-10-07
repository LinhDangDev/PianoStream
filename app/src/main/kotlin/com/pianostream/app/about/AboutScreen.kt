package com.pianostream.app.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pianostream.BuildConfig
import com.pianostream.core.designsystem.component.SectionHeader

/**
 * About — the disclosure surface Phase 5b asserts on (its presence and the
 * clause texts below are contractual; Phase 5b edits nothing here):
 * YouTube egress contents, cache caps, history caps, backup policy,
 * updater opt-in (github dist only), and the ToS disclaimer.
 */
@Composable
public fun AboutScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item { SectionHeader("PianoStream") }
        item {
            DisclosureRow(
                "Local-first playback of your own FLAC+CUE collection and " +
                    "YouTube Music, on-device only. No account, no backend, " +
                    "no analytics.",
            )
        }

        item { SectionHeader("What leaves the device") }
        item {
            DisclosureRow(
                "When you search or stream YouTube Music, your query, " +
                    "Accept-Language, IP address and visitorData are sent to " +
                    "YouTube/Google.",
            )
        }
        item {
            DisclosureRow(
                "Search suggestions are off unless you enable them; \"This " +
                    "device only\" search makes zero network calls.",
            )
        }
        if (BuildConfig.UPDATE_ASSET.isNotEmpty()) {
            item {
                DisclosureRow(
                    "In-app updates are opt-in. When enabled, release checks " +
                        "contact ${BuildConfig.REGISTRY_HOSTS} and downloads a " +
                        "signed APK that is verified before install.",
                )
            }
        }

        item { SectionHeader("What stays on the device") }
        item {
            DisclosureRow(
                "Streamed audio is cached up to your configured budget and " +
                    "can be cleared from Settings → Storage.",
            )
        }
        item {
            DisclosureRow(
                "Play history is capped at 500 entries / 90 days. Backup is " +
                    "disabled — nothing is uploaded to device-backup channels.",
            )
        }

        item { SectionHeader("Disclaimer") }
        item {
            DisclosureRow(
                "PianoStream is a personal, local player. It is not " +
                    "affiliated with YouTube or Google. You are responsible " +
                    "for complying with YouTube's Terms of Service and " +
                    "applicable law when streaming third-party content, and " +
                    "for the rights to the local files you play.",
            )
        }
    }
}

@Composable
private fun DisclosureRow(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
