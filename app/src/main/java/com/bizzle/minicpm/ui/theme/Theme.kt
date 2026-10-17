package com.bizzle.minicpm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.bizzle.minicpm.core.ThemeSpec

val NeonGreen = Color(0xFF39FF14)
val Danger = Color(0xFFFF5370)
val Warn = Color(0xFFFFC857)

data class Palette(val accent: Color, val bg: Color, val surface: Color, val text: Color, val name: String) {
    val onAccent: Color get() = if (0.2126f * accent.red + 0.7152f * accent.green + 0.0722f * accent.blue > 0.5f) Color.Black else Color.White
}

val LocalPalette = staticCompositionLocalOf { Palette(NeonGreen, Color.Black, Color(0xFF0B140B), Color(0xFFD6FFCC), "Neon v4.20") }

fun ThemeSpec.palette() = Palette(Color(primary), Color(background), Color(surface), Color(onSurface), name)

@Composable
fun BizzleTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val p = spec.palette()
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent, onSecondary = p.onAccent, tertiary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surface, onSurfaceVariant = p.text, outline = p.accent.copy(alpha = 0.6f),
        primaryContainer = p.surface, onPrimaryContainer = p.accent, error = Danger, onError = Color.Black,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
        surfaceContainerLow = p.bg, surfaceContainerLowest = p.bg, inverseSurface = p.accent, inverseOnSurface = p.onAccent,
    )
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
