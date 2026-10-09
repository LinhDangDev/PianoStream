package com.pianostream.app.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianostream.BuildConfig
import com.pianostream.core.data.settings.SettingsDataStore.ReplayGainMode
import com.pianostream.core.data.settings.SettingsDataStore.TaskRemovalPolicy
import com.pianostream.core.data.settings.SettingsDataStore.ThemeMode
import com.pianostream.core.designsystem.component.EmptyState
import com.pianostream.core.designsystem.component.SectionHeader
import androidx.compose.material3.ButtonDefaults
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.theme.Aurora
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings — every frozen `SettingsDataStore` key reachable (C5): a search
 * field on top that live-filters every row by label (section headers hide
 * when all their rows are filtered out), then Appearance, Playback, Search &
 * network (flavor-bound updater row, F2/F15), Storage, Library, Danger zone.
 * Phase 5b asserts the disclosure rows exist and edits none of them.
 *
 * Presentation: grouped neumorphic cards — one raised charcoal card per
 * section with hairline-separated rows, same recipe as the About disclosure
 * cards (the turntable language, after dribbble.com/shots/12239558). The
 * search/playback layout follows Spotify Settings (search field, then a
 * Playback group carrying audio quality, gapless/mono/normalize, equalizer).
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
    val context = LocalContext.current

    // SAF document pickers — same StartActivityForResult pattern as
    // LibraryRootPicker (the typed contracts can't name a mime set).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let(viewModel::exportBackup)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let(viewModel::restoreBackup)
    }
    val onExportBackup = {
        exportLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, suggestedBackupName()),
        )
    }
    val onRestoreBackup = {
        restoreLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/octet-stream"),
                ),
        )
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            placeholder = { Text("Search settings") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = Aurora.TextSecondary,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Vinyl.Gold,
                unfocusedBorderColor = Aurora.Hairline,
                cursorColor = Vinyl.Gold,
                focusedTextColor = Aurora.TextPrimary,
                unfocusedTextColor = Aurora.TextPrimary,
                focusedPlaceholderColor = Aurora.TextSecondary,
                unfocusedPlaceholderColor = Aurora.TextSecondary,
                unfocusedContainerColor = Color(0xFF1E1F23),
                focusedContainerColor = Color(0xFF1E1F23),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val sections = buildSections(
            state, viewModel, context.equalizerOpener(),
            onOpenAbout, onOpenUpdater, onRevokeLibrary, onClearStreamCache,
            onExportBackup, onRestoreBackup,
        )

        val query = state.searchQuery.trim()
        val visible = if (query.isEmpty()) {
            sections
        } else {
            sections.mapNotNull { section ->
                val rows = section.rows.filter {
                    it.label.contains(query, ignoreCase = true)
                }
                if (rows.isEmpty()) null else section.copy(rows = rows)
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        title = "No matching settings",
                        body = null,
                    )
                }
            } else {
                items(visible, key = { it.title }) { section ->
                    SettingsGroup(title = section.title) {
                        section.rows.forEachIndexed { i, row ->
                            row.content()
                            if (i < section.rows.lastIndex) RowDivider()
                        }
                    }
                }
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

    if (state.confirmClearDownloads) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClearDownloads,
            containerColor = Vinyl.BgTop,
            title = { Text("Clear all downloads?") },
            text = {
                Text(
                    "Removes ${state.downloadCount} downloaded tracks " +
                        "(${formatBytes(state.downloadBytes)}) from this " +
                        "device. Streamed playback is unaffected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmClearDownloads,
                    colors = ButtonDefaults.textButtonColors(contentColor = Vinyl.Gold),
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelClearDownloads) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Launches the OS audio-effects panel, or shows a toast when none exists. */
private fun android.content.Context.equalizerOpener(): () -> Unit = {
    try {
        startActivity(
            Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL"),
        )
    } catch (e: Exception) {
        Toast.makeText(this, "No equalizer found", Toast.LENGTH_SHORT).show()
    }
}

/** `Music-backup-<yyyyMMdd-HHmm>.psb` — the CreateDocument suggested name. */
private fun suggestedBackupName(): String =
    "Music-backup-" +
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) +
        ".psb"

/** Byte count → display string ("412 KB", "3.2 MB", "1.1 GB"). */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** Display labels for the persisted `audioItagTier` values. */
private val AUDIO_QUALITY_LABELS = mapOf(
    "AUTO" to "Auto",
    "LOW" to "Low (~48k)",
    "NORMAL" to "Normal (128k)",
    "HIGH" to "High (~160k)",
)

private fun buildSections(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    openEqualizer: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUpdater: () -> Unit,
    onRevokeLibrary: () -> Unit,
    onClearStreamCache: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
): List<SettingSection> = listOf(
    SettingSection(
        title = "Appearance",
        rows = listOf(
            SettingRow("Theme") {
                ChoiceRow(
                    title = "Theme",
                    value = state.themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                    choices = ThemeMode.entries.toList(),
                    label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    onPick = viewModel::setThemeMode,
                )
            },
            SettingRow("Dynamic color") {
                ToggleRow(
                    title = "Dynamic color",
                    subtitle = "Wallpaper-based colors (Android 12+)",
                    checked = state.dynamicColor,
                    onToggle = viewModel::setDynamicColor,
                )
            },
        ),
    ),
    SettingSection(
        title = "Playback",
        rows = listOf(
            SettingRow("ReplayGain") {
                ChoiceRow(
                    title = "ReplayGain",
                    subtitle = "OFF restores audio offload",
                    value = state.replayGainMode.name,
                    choices = ReplayGainMode.entries.toList(),
                    label = { it.name },
                    onPick = viewModel::setReplayGainMode,
                )
            },
            SettingRow("ReplayGain preamp") {
                SliderRow(
                    title = "ReplayGain preamp",
                    valueLabel = "${state.preampDb} dB",
                    value = state.preampDb.toFloat(),
                    range = -12f..12f,
                    onChange = { viewModel.setPreampDb(it.toDouble()) },
                )
            },
            SettingRow("YouTube audio quality") {
                ChoiceRow(
                    title = "YouTube audio quality",
                    value = AUDIO_QUALITY_LABELS[state.audioItagTier]
                        ?: state.audioItagTier,
                    choices = AUDIO_QUALITY_LABELS.keys.toList(),
                    label = { AUDIO_QUALITY_LABELS[it] ?: it },
                    onPick = viewModel::setAudioItagTier,
                )
            },
            SettingRow("Preferred stream quality") {
                ChoiceRow(
                    title = "Preferred stream quality",
                    value = "itag ${state.preferredItag}",
                    choices = listOf(251, 250, 249, 140),
                    label = { "itag $it" },
                    onPick = viewModel::setPreferredItag,
                )
            },
            SettingRow("Crossfade") {
                SliderRow(
                    title = "Crossfade",
                    valueLabel = "${state.crossfadeMs} ms",
                    value = state.crossfadeMs.toFloat(),
                    range = 0f..12_000f,
                    onChange = { viewModel.setCrossfadeMs(it.toInt()) },
                )
            },
            SettingRow("Mono audio") {
                ToggleRow(
                    title = "Mono audio",
                    subtitle = "Merge stereo into one channel",
                    checked = state.monoAudio,
                    onToggle = viewModel::setMonoAudio,
                )
            },
            SettingRow("Gapless playback") {
                ToggleRow(
                    title = "Gapless playback",
                    subtitle = "Seamless track transitions",
                    checked = state.gapless,
                    onToggle = viewModel::setGapless,
                )
            },
            SettingRow("Normalize volume") {
                ToggleRow(
                    title = "Normalize volume",
                    subtitle = "Even out loudness across tracks",
                    checked = state.normalizeVolume,
                    onToggle = viewModel::setNormalizeVolume,
                )
            },
            SettingRow("Equalizer") {
                ActionRow(
                    title = "Equalizer",
                    subtitle = "System sound effects",
                    icon = Icons.Filled.Tune,
                    onClick = openEqualizer,
                )
            },
            SettingRow("When the app is swiped away") {
                ChoiceRow(
                    title = "When the app is swiped away",
                    value = state.taskRemovalPolicy.name,
                    choices = TaskRemovalPolicy.entries.toList(),
                    label = { it.name },
                    onPick = viewModel::setTaskRemovalPolicy,
                )
            },
        ),
    ),
    SettingSection(
        title = "Search & network",
        rows = listOf(
            SettingRow("This device only") {
                ToggleRow(
                    title = "This device only",
                    subtitle = "Search never touches the network",
                    checked = state.localOnlySearch,
                    onToggle = viewModel::setLocalOnlySearch,
                )
            },
            SettingRow("Search suggestions") {
                ToggleRow(
                    title = "Search suggestions",
                    subtitle = "Sends your query to YouTube while typing",
                    checked = state.sendSearchSuggestions,
                    onToggle = viewModel::setSendSearchSuggestions,
                )
            },
            SettingRow("In-app updates") {
                if (BuildConfig.UPDATE_ASSET.isNotEmpty()) {
                    ToggleRow(
                        title = "In-app updates",
                        subtitle = "GitHub builds check your release channel",
                        checked = state.updaterEnabled,
                        onToggle = {
                            if (it) onOpenUpdater() else viewModel.setUpdaterEnabled(false)
                        },
                    )
                } else {
                    ToggleRow(
                        title = "In-app updates",
                        subtitle = "GitHub build only",
                        checked = false,
                        onToggle = {},
                        enabled = false,
                    )
                }
            },
            SettingRow("Remote player config") {
                ToggleRow(
                    title = "Remote player config",
                    subtitle = "YouTube player.js config refresh",
                    checked = state.remoteConfigEnabled,
                    onToggle = viewModel::requestRemoteConfig,
                )
            },
        ),
    ),
    SettingSection(
        title = "Storage",
        rows = listOf(
            SettingRow("Stream cache budget") {
                SliderRow(
                    title = "Stream cache budget",
                    valueLabel = "${state.cacheBudgetBytes / (1024 * 1024)} MB",
                    value = (state.cacheBudgetBytes / (1024 * 1024)).toFloat(),
                    range = 256f..8192f,
                    onChange = { viewModel.setCacheBudgetBytes(it.toLong() * 1024 * 1024) },
                )
            },
            SettingRow("Clear stream cache") {
                ActionRow(title = "Clear stream cache", onClick = {
                    viewModel.clearStreamCache(); onClearStreamCache()
                })
            },
            SettingRow("Downloads") {
                ActionRow(
                    title = "Downloads",
                    subtitle = "${state.downloadCount} items · " +
                        formatBytes(state.downloadBytes),
                    onClick = {},
                    enabled = false,
                )
            },
            SettingRow("Clear all downloads") {
                ActionRow(
                    title = "Clear all downloads",
                    subtitle = "Deletes every downloaded file",
                    onClick = viewModel::requestClearDownloads,
                    enabled = state.downloadCount > 0,
                )
            },
        ),
    ),
    SettingSection(
        title = "Backup & restore",
        rows = listOfNotNull(
            SettingRow("Include downloaded audio files") {
                ToggleRow(
                    title = "Include downloaded audio files",
                    subtitle = "Copies the audio files into the .psb archive",
                    checked = state.includeBackupDownloads,
                    onToggle = viewModel::setIncludeBackupDownloads,
                    enabled = !state.backupRunning,
                )
            },
            SettingRow("Export backup") {
                ActionRow(
                    title = "Export backup",
                    subtitle = "Playlists, favorites, history, settings, downloads",
                    onClick = onExportBackup,
                    enabled = !state.backupRunning,
                )
            },
            SettingRow("Restore backup") {
                ActionRow(
                    title = "Restore backup",
                    subtitle = "Merges a .psb backup into this device",
                    onClick = onRestoreBackup,
                    enabled = !state.backupRunning,
                )
            },
            state.backupStatus?.let { status ->
                SettingRow("Backup status") {
                    ActionRow(
                        title = status,
                        onClick = {},
                        enabled = false,
                    )
                }
            },
        ),
    ),
    SettingSection(
        title = "Library",
        rows = listOf(
            SettingRow("Library folder") {
                ActionRow(
                    title = "Library folder",
                    subtitle = state.libraryRootTreeUri ?: "Not set",
                    onClick = {},
                    enabled = false,
                )
            },
            SettingRow("Revoke library access") {
                ActionRow(
                    title = "Revoke library access",
                    subtitle = "Releases the folder permission",
                    onClick = onRevokeLibrary,
                )
            },
        ),
    ),
    SettingSection(
        title = "About",
        rows = listOf(
            SettingRow("About PianoStream") {
                ActionRow(title = "About PianoStream", onClick = onOpenAbout)
            },
        ),
    ),
    SettingSection(
        title = "Danger zone",
        rows = listOf(
            SettingRow("Delete all local data") {
                ActionRow(
                    title = "Delete all local data",
                    subtitle = "Wipes databases, settings, caches — everything",
                    onClick = viewModel::requestDeleteAll,
                    danger = true,
                )
            },
        ),
    ),
)

/** One searchable settings row — [label] is what the search field matches. */
private data class SettingRow(
    val label: String,
    val content: @Composable () -> Unit,
)

/** A group of rows rendered under one section header inside one card. */
private data class SettingSection(
    val title: String,
    val rows: List<SettingRow>,
)

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
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Aurora.TextPrimary)
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
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Aurora.TextPrimary)
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
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Aurora.TextPrimary)
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
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = if (enabled) Vinyl.GoldDim else Aurora.TextSecondary,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(20.dp),
            )
        }
        Column {
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
}
