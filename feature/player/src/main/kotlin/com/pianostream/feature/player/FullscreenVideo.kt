package com.pianostream.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.pianostream.core.designsystem.component.Vinyl
import com.pianostream.core.designsystem.component.formatDuration
import com.pianostream.core.designsystem.theme.Aurora
import com.pianostream.core.media.video.VideoQualityStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Fullscreen landscape video, framed after YouTube Music's fullscreen
 * overlay: the picture fills the screen edge-to-edge on black, and a
 * tap toggles a scrimmed chrome — close + title/artist up top, seek,
 * transport, quality chip and exit-fullscreen along the bottom — that
 * auto-hides after ~3.5 s of no touch.
 *
 * Rendered as a [Dialog] (no route/navigation coupling). While open the
 * activity is locked to landscape and the system bars go immersive-sticky;
 * both are restored on dispose. The SAME [player] instance is re-surfaced
 * here — never a second ExoPlayer — so playback continues seamlessly. The
 * caller owns remounting any other PlayerSurface that lost the output
 * (Media3 binds the last-attached surface).
 *
 * Quality wiring: pass [videoQuality]/[onSelectQuality] to mirror the
 * caller's state (e.g. `PlayerViewModel::setVideoQuality`). When omitted,
 * the composable resolves the app-scoped [VideoQualityStore] itself and
 * applies the same replace+reseek pattern the ViewModel uses.
 */
@Composable
internal fun FullscreenVideo(
    player: Player?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    videoQuality: Int? = null,
    onSelectQuality: ((Int?) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        FullscreenVideoContent(
            player = player,
            onClose = onClose,
            modifier = modifier,
            videoQuality = videoQuality,
            onSelectQuality = onSelectQuality,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideoContent(
    player: Player?,
    onClose: () -> Unit,
    modifier: Modifier,
    videoQuality: Int?,
    onSelectQuality: ((Int?) -> Unit)?,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window

    // Landscape lock + immersive-sticky for the lifetime of the dialog.
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = dialogWindow?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, view)
        }
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Live playback state — polled off the shared Player instance.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0L
            durationMs = player?.duration?.coerceAtLeast(0) ?: 0L
            playing = player?.isPlaying == true
            val meta = player?.currentMediaItem?.mediaMetadata
            title = meta?.title?.toString().orEmpty()
            artist = meta?.artist?.toString().orEmpty()
            delay(500)
        }
    }

    // Overlay visibility + auto-hide. `touchTick` resets the 3.5 s timer on
    // any chrome interaction so controls never vanish mid-gesture.
    var controls by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controls, touchTick) {
        if (controls) {
            delay(HIDE_DELAY_MS)
            controls = false
        }
    }

    // Quality tier: explicit params first, else the app-scoped store.
    val qualityStore = remember {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                VideoQualityEntryPoint::class.java,
            ).videoQualityStore()
        }.getOrNull()
    }
    val storeItag = qualityStore?.itag?.collectAsStateWithLifecycle()?.value
    val itag = videoQuality ?: storeItag
    val selectQuality = onSelectQuality ?: { tier: Int? ->
        qualityStore?.set(tier)
        player?.let { p ->
            p.currentMediaItem?.let { item ->
                val index = p.currentMediaItemIndex
                p.replaceMediaItem(index, item)
                p.seekTo(index, p.currentPosition.coerceAtLeast(0))
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    controls = !controls
                    touchTick += 1
                }
            },
    ) {
        if (player != null) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // ---- top scrim: close + title/artist ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                            ),
                        )
                        .padding(start = 4.dp, top = 4.dp, end = 16.dp, bottom = 28.dp),
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close fullscreen",
                            tint = Aurora.TextPrimary,
                        )
                    }
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            title,
                            color = Aurora.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                color = Vinyl.GoldDim,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // ---- bottom scrim: seek, times, transport, quality, exit ----
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f)),
                            ),
                        )
                        .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 14.dp),
                ) {
                    GoldSlider(
                        fraction = if (durationMs > 0) {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else 0f,
                        onChange = { f ->
                            touchTick += 1
                            if (durationMs > 0) player?.seekTo((f * durationMs).toLong())
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Text(formatDuration(positionMs), color = Vinyl.GoldDim, fontSize = 12.sp)
                        Text(formatDuration(durationMs), color = Vinyl.GoldDim, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        NeuButton(
                            icon = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous track",
                            onClick = {
                                touchTick += 1
                                player?.seekToPreviousMediaItem()
                            },
                            size = 46.dp,
                            iconSize = 22.dp,
                            tint = Vinyl.GoldDim,
                        )
                        Spacer(Modifier.width(28.dp))
                        NeuButton(
                            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            onClick = {
                                touchTick += 1
                                player?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            size = 64.dp,
                            iconSize = 30.dp,
                            tint = Vinyl.Gold,
                            ring = true,
                        )
                        Spacer(Modifier.width(28.dp))
                        NeuButton(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Next track",
                            onClick = {
                                touchTick += 1
                                player?.seekToNextMediaItem()
                            },
                            size = 46.dp,
                            iconSize = 22.dp,
                            tint = Vinyl.GoldDim,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (onSelectQuality != null || qualityStore != null) {
                            VideoQualityChip(
                                currentItag = itag,
                                onSelect = { tier ->
                                    touchTick += 1
                                    selectQuality(tier)
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Filled.FullscreenExit,
                                contentDescription = "Exit fullscreen",
                                tint = Aurora.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface VideoQualityEntryPoint {
    fun videoQualityStore(): VideoQualityStore
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val HIDE_DELAY_MS = 3_500L
