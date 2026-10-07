package com.pianostream.app.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pianostream.BuildConfig
import com.pianostream.core.designsystem.component.SectionHeader
import com.pianostream.core.designsystem.component.VinylDisc
import com.pianostream.core.designsystem.theme.Aurora

/**
 * About — the disclosure surface Phase 5b asserts on (its presence and the
 * clause texts below are contractual; Phase 5b edits nothing here):
 * YouTube egress contents, cache caps, history caps, backup policy,
 * updater opt-in (github dist only), and the ToS disclaimer.
 *
 * Presentation: grouped neumorphic disclosure cards — one raised charcoal
 * card per section with a gold title and hairline-separated clauses (the
 * turntable language: skeuomorphic dark grouped cards, after
 * dribbble.com/shots/8557373 + dribbble.com/shots/12239558).
 */
@Composable
public fun AboutScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            DisclosureCard(title = "PianoStream", emblem = true) {
                DisclosureRow(
                    "Local-first playback of your own FLAC+CUE collection and " +
                        "YouTube Music, on-device only. No account, no backend, " +
                        "no analytics.",
                )
            }
        }

        item {
            DisclosureCard(title = "What leaves the device") {
                DisclosureRow(
                    "When you search or stream YouTube Music, your query, " +
                        "Accept-Language, IP address and visitorData are sent to " +
                        "YouTube/Google.",
                )
                ClauseDivider()
                DisclosureRow(
                    "Search suggestions are off unless you enable them; \"This " +
                        "device only\" search makes zero network calls.",
                )
                if (BuildConfig.UPDATE_ASSET.isNotEmpty()) {
                    ClauseDivider()
                    DisclosureRow(
                        "In-app updates are opt-in. When enabled, release checks " +
                            "contact ${BuildConfig.REGISTRY_HOSTS} and downloads a " +
                            "signed APK that is verified before install.",
                    )
                }
            }
        }

        item {
            DisclosureCard(title = "What stays on the device") {
                DisclosureRow(
                    "Streamed audio is cached up to your configured budget and " +
                        "can be cleared from Settings → Storage.",
                )
                ClauseDivider()
                DisclosureRow(
                    "Play history is capped at 500 entries / 90 days. Backup is " +
                        "disabled — nothing is uploaded to device-backup channels.",
                )
            }
        }

        item {
            DisclosureCard(title = "Disclaimer") {
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
}

/**
 * Raised charcoal card: barely-lifted surface (soft drop shadow, top-left
 * light edge baked into the gradient), hairline border, generous corner
 * radius — one card per disclosure section. [emblem] puts a small vinyl
 * record on top for the brand card.
 */
@Composable
private fun DisclosureCard(
    title: String,
    modifier: Modifier = Modifier,
    emblem: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A2D32), Color(0xFF1B1C1F)),
                ),
            )
            .border(0.5.dp, Aurora.Hairline, shape)
            .padding(bottom = 10.dp),
    ) {
        if (emblem) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                VinylDisc(
                    artworkUri = null,
                    title = "PianoStream",
                    modifier = Modifier.size(88.dp),
                    artworkContentDescription = null,
                )
            }
        }
        SectionHeader(title)
        content()
    }
}

/** Hairline inside a card between clauses. */
@Composable
private fun ClauseDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = Aurora.Hairline,
    )
}

@Composable
private fun DisclosureRow(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Aurora.TextSecondary)
    }
}
