package com.pianostream.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Aurora design tokens — the design language ported from Melody
 * (`AppMusic/melody-native`): pure-black canvas, translucent glass fills,
 * hairline borders, a single vivid accent.
 *
 * Accent is user-switchable in Melody; PianoStream keeps the same preset set
 * and the `applyAccent` hook but binds it to a compile-time default until a
 * settings surface for it exists (theme prefs are Interface-Frozen — an
 * accent key is a plan amendment, not a quiet add).
 */
data class AccentPreset(val key: String, val label: String, val accent: Color, val soft: Color)

val AccentPresets = listOf(
    AccentPreset("apple", "Apple Red", Color(0xFFFC3C44), Color(0xFFFA5C66)),
    AccentPreset("mono", "Mono", Color(0xFFFFFFFF), Color(0xFFCFCFCF)),
    AccentPreset("violet", "Violet", Color(0xFFB9A6F5), Color(0xFF9C86E8)),
    AccentPreset("lime", "Lime", Color(0xFFD4E157), Color(0xFFAFB42B)),
    AccentPreset("ice", "Ice", Color(0xFF7FD8F7), Color(0xFF4FB3D9)),
    AccentPreset("rose", "Rose", Color(0xFFF48FB1), Color(0xFFEC6A96)),
)

object Aurora {
    val BgTop = Color(0xFF000000)
    val Bg = Color(0xFF000000)
    val BgDeep = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceLight = Color(0xFF2C2C2E)
    val Glass = Color(0x14FFFFFF)           // translucent card fill
    val GlassStrong = Color(0xF21C1C1E)     // frosted bar fill (near-opaque)
    val Hairline = Color(0x26FFFFFF)        // hairline border
    var Accent by mutableStateOf(Color(0xFFB9A6F5))
    var AccentSoft by mutableStateOf(Color(0xFF9C86E8))

    /** Text/icon color on top of the accent fill — black on light accents. */
    val onAccent: Color get() = if (Accent.luminance() > 0.55f) Color.Black else Color.White
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF98989F)
    val White = Color(0xFFFFFFFF)
    val Error = Color(0xFFFF6961)

    val backgroundBrush = Brush.verticalGradient(listOf(BgTop, Bg, BgDeep))

    fun applyAccent(key: String) {
        val p = AccentPresets.firstOrNull { it.key == key } ?: AccentPresets[2]
        Accent = p.accent
        AccentSoft = p.soft
    }
}
