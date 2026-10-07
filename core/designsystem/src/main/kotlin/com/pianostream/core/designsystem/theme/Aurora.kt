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
 * (`AppMusic/melody-native`), retuned for the turntable look the player
 * adopted: a charcoal neumorphic canvas, warm translucent glass fills,
 * hairline borders, and a champagne-gold accent ("Neumorphism Vinyl
 * Player" by Marikutsa Andriy — dribbble.com/shots/12239558).
 *
 * Accent is user-switchable in Melody; PianoStream keeps the same preset set
 * and the `applyAccent` hook but binds it to a compile-time default until a
 * settings surface for it exists (theme prefs are Interface-Frozen — an
 * accent key is a plan amendment, not a quiet add).
 */
data class AccentPreset(val key: String, val label: String, val accent: Color, val soft: Color)

val AccentPresets = listOf(
    AccentPreset("champagne", "Champagne", Color(0xFFE2BE98), Color(0xFF9E7E66)),
    AccentPreset("apple", "Apple Red", Color(0xFFFC3C44), Color(0xFFFA5C66)),
    AccentPreset("mono", "Mono", Color(0xFFFFFFFF), Color(0xFFCFCFCF)),
    AccentPreset("violet", "Violet", Color(0xFFB9A6F5), Color(0xFF9C86E8)),
    AccentPreset("lime", "Lime", Color(0xFFD4E157), Color(0xFFAFB42B)),
    AccentPreset("ice", "Ice", Color(0xFF7FD8F7), Color(0xFF4FB3D9)),
    AccentPreset("rose", "Rose", Color(0xFFF48FB1), Color(0xFFEC6A96)),
)

object Aurora {
    val BgTop = Color(0xFF232528)
    val Bg = Color(0xFF1B1C1F)
    val BgDeep = Color(0xFF141518)
    val Surface = Color(0xFF232528)
    val SurfaceLight = Color(0xFF2E3136)
    val Glass = Color(0x14FFFFFF)           // translucent card fill
    val GlassStrong = Color(0xF2222327)     // frosted bar fill (near-opaque)
    val Hairline = Color(0x26FFFFFF)        // hairline border
    var Accent by mutableStateOf(Color(0xFFE2BE98))
    var AccentSoft by mutableStateOf(Color(0xFF9E7E66))

    /** Text/icon color on top of the accent fill — black on light accents. */
    val onAccent: Color get() = if (Accent.luminance() > 0.55f) Color.Black else Color.White
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFC7C2B8)
    val White = Color(0xFFFFFFFF)
    val Error = Color(0xFFFF6961)

    val backgroundBrush = Brush.verticalGradient(listOf(BgTop, Bg, BgDeep))

    fun applyAccent(key: String) {
        val p = AccentPresets.firstOrNull { it.key == key } ?: AccentPresets[0]
        Accent = p.accent
        AccentSoft = p.soft
    }
}
