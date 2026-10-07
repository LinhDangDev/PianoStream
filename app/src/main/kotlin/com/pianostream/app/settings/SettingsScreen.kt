package com.pianostream.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.BuildConfig
import com.pianostream.core.data.settings.SettingsDataStore.ReplayGainMode
import com.pianostream.core.data.settings.SettingsDataStore.TaskRemovalPolicy
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.designsystem.component.SectionHeader
import androidx.compose.material3.ButtonDefaults
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora

/**
 * Settings — every frozen `SettingsDataStore` key reachable (C5): Appearance,
 * Playback, Search & network (flavor-bound updater row, F2/F15), Storage,
 * Library, Danger zone. Phase 5b asserts the disclosure rows exist and edits
 * none of them.
 *
 * Presentation: grouped neumorphic cards — one raised charcoal card per
 * section with hairline-separated rows, same recipe as the About disclosure
 * cards (the turntable language, after dribbble.com/shots/12239558).
 */
@Composable
public fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onOpenUpdater: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onRevokeLibrary: () -> Unit = {},
    onClearStreamCache: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsGroup(title = "Appearance") {
                ChoiceRow(
                    title = "Theme",
                    value = state.themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                    choices = ThemeMode.entries.toList(),
                    label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    onPick = viewModel::setThemeMode,
                )
                RowDivider()
                ToggleRow(
                    title = "Dynamic color",
                    subtitle = "Wallpaper-based colors (Android 12+)",
                    checked = state.dynamicColor,
                    onToggle = viewModel::setDynamicColor,
                )
            }
        }

        item {
            SettingsGroup(title = "Playback") {
                ChoiceRow(
                    title = "ReplayGain",
                    subtitle = "OFF restores audio offload",
                    value = state.replayGainMode.name,
                    choices = ReplayGainMode.entries.toList(),
                    label = { it.name },
                    onPick = viewModel::setReplayGainMode,
                )
                RowDivider()
                SliderRow(
                    title = "ReplayGain preamp",
                    valueLabel = "${state.preampDb} dB",
                    value = state.preampDb.toFloat(),
                    range = -12f..12f,
                    onChange = { viewModel.setPreampDb(it.toDouble()) },
                )
                RowDivider()
                SliderRow(
                    title = "Crossfade",
                    valueLabel = "${state.crossfadeMs} ms",
                    value = state.crossfadeMs.toFloat(),
                    range = 0f..12_000f,
                    onChange = { viewModel.setCrossfadeMs(it.toInt()) },
                )
                RowDivider()
                ChoiceRow(
                    title = "When the app is swiped away",
                    value = state.taskRemovalPolicy.name,
                    choices = TaskRemovalPolicy.entries.toList(),
                    label = { it.name },
                    onPick = viewModel::setTaskRemovalPolicy,
                )
                RowDivider()
                ChoiceRow(
                    title = "Preferred stream quality",
                    value = "itag ${state.preferredItag}",
                    choices = listOf(251, 250, 249, 140),
                    label = { "itag $it" },
                    onPick = viewModel::setPreferredItag,
                )
            }
        }

        item {
            SettingsGroup(title = "Search & network") {
                ToggleRow(
                    title = "This device only",
                    subtitle = "Search never touches the network",
                    checked = state.localOnlySearch,
                    onToggle = viewModel::setLocalOnlySearch,
                )
                RowDivider()
                ToggleRow(
                    title = "Search suggestions",
                    subtitle = "Sends your query to YouTube while typing",
                    checked = state.sendSearchSuggestions,
                    onToggle = viewModel::setSendSearchSuggestions,
                )
                if (BuildConfig.UPDATE_ASSET.isNotEmpty()) {
                    RowDivider()
                    ToggleRow(
                        title = "In-app updates",
                        subtitle = "GitHub builds check your release channel",
                        checked = state.updaterEnabled,
                        onToggle = {
                            if (it) onOpenUpdater() else viewModel.setUpdaterEnabled(false)
                        },
                    )
                } else {
                    RowDivider()
                    ToggleRow(
                        title = "In-app updates",
                        subtitle = "GitHub build only",
                        checked = false,
                        onToggle = {},
                        enabled = false,
                    )
                }
                RowDivider()
                ToggleRow(
                    title = "Remote player config",
                    subtitle = "YouTube player.js config refresh",
                    checked = state.remoteConfigEnabled,
                    onToggle = viewModel::requestRemoteConfig,
                )
            }
        }

        item {
            SettingsGroup(title = "Storage") {
                SliderRow(
                    title = "Stream cache budget",
                    valueLabel = "${state.cacheBudgetBytes / (1024 * 1024)} MB",
                    value = (state.cacheBudgetBytes / (1024 * 1024)).toFloat(),
                    range = 256f..8192f,
                    onChange = { viewModel.setCacheBudgetBytes(it.toLong() * 1024 * 1024) },
                )
                RowDivider()
                ActionRow(title = "Clear stream cache", onClick = {
                    viewModel.clearStreamCache(); onClearStreamCache()
                })
            }
        }

        item {
            SettingsGroup(title = "Library") {
                ActionRow(
                    title = "Library folder",
                    subtitle = state.libraryRootTreeUri ?: "Not set",
                    onClick = {},
                    enabled = false,
                )
                RowDivider()
                ActionRow(
                    title = "Revoke library access",
                    subtitle = "Releases the folder permission",
                    onClick = onRevokeLibrary,
                )
            }
        }

        item {
            SettingsGroup(title = "About") {
                ActionRow(title = "About PianoStream", onClick = onOpenAbout)
            }
        }

        item {
            SettingsGroup(title = "Danger zone") {
                ActionRow(
                    title = "Delete all local data",
                    subtitle = "Wipes databases, settings, caches — everything",
                    onClick = viewModel::requestDeleteAll,
                    danger = true,
                )
            }
        }
    }

    // F2/F15: remote-config consent names the pinned registry host.
    if (state.confirmRemoteConfig) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmRemoteConfig(false) },
            containerColor = Vinyl.BgTop,
            title = { Text("Enable remote config?") },
            text = {
                Text(
                    "PianoStream will fetch the YouTube player config from " +
                        "${viewModel.registryHosts}. Requests include your IP " +
                        "address and Accept-Language.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRemoteConfig(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmRemoteConfig(false) }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteAll,
            containerColor = Vinyl.BgTop,
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    "This permanently wipes your queue, history, favorites, " +
                        "library index, settings and caches, and revokes the " +
                        "library folder permission. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDeleteAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) {
                    Text(if (state.deleteAllRunning) "Deleting…" else "Delete everything")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeleteAll) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Raised charcoal card holding one settings section — identical recipe to the
 * About `DisclosureCard` (soft shadow, lifted gradient, hairline border,
 * 20dp corners) so both screens read as one design language.
 */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
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
            .padding(bottom = 6.dp),
    ) {
        SectionHeader(title)
        content()
    }
}

/** Hairline inside a card between rows. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = Aurora.Hairline,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Aurora.TextSecondary,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Vinyl.Gold,
                checkedThumbColor = Vinyl.BgBottom,
                uncheckedTrackColor = Vinyl.BgBottom,
                uncheckedThumbColor = Vinyl.GoldDim,
            ),
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    value: String,
    choices: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { expanded = true }
            .padding(horizontal = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Aurora.TextSecondary,
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Vinyl.Gold,
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = Color(0xFF232529),
            tonalElevation = 0.dp,
        ) {
            choices.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label(c),
                            color = if (label(c) == value) Vinyl.Gold
                            else Aurora.TextPrimary,
                        )
                    },
                    onClick = { onPick(c); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = Vinyl.Gold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Vinyl.Gold,
                activeTrackColor = Vinyl.Gold,
                inactiveTrackColor = Vinyl.BgBottom,
            ),
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (!enabled) Aurora.TextSecondary
            else if (danger) Color(0xFFD97B6C)
            else Aurora.TextPrimary,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Aurora.TextSecondary,
            )
        }
    }
}
