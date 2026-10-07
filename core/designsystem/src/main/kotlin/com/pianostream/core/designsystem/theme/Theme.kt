package com.pianostream.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * PianoStream theme — the turntable design language. The curated palettes
 * below are mapped from the Aurora tokens (charcoal canvas, glass surfaces,
 * champagne-gold accent). `dynamicColor` still wins when the frozen setting
 * is on (Material You); turn it off in Settings for the full vinyl look.
 */

private val AuroraDark = darkColorScheme(
    primary = Color(0xFFE2BE98),
    onPrimary = Color(0xFF3D2C1A),
    primaryContainer = Color(0xFF57422C),
    onPrimaryContainer = Color(0xFFF7DFC6),
    secondary = Color(0xFFC7C2B8),
    onSecondary = Color(0xFF1B1C1F),
    secondaryContainer = Color(0xFF2E3136),
    onSecondaryContainer = Color(0xFFE4E1E9),
    tertiary = Color(0xFF9E7E66),
    onTertiary = Color(0xFF2C1F14),
    background = Color(0xFF17181B),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1B1C1F),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF2E3136),
    onSurfaceVariant = Color(0xFFC7C2B8),
    error = Color(0xFFFF6961),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AuroraLight = lightColorScheme(
    primary = Color(0xFF5E4BA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4DBFF),
    onPrimaryContainer = Color(0xFF1A1052),
    secondary = Color(0xFF5F5E66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E1E9),
    onSecondaryContainer = Color(0xFF1C1B1F),
    tertiary = Color(0xFF7158CC),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1B1B20),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE6E1EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun PianoStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)

        darkTheme -> AuroraDark
        else -> AuroraLight
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = PianoStreamTypography,
        shapes = PianoStreamShapes,
        content = content,
    )
}
