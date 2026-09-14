package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val DarkColorScheme = darkColorScheme(
    primary = CaravanBlue,
    onPrimary = NightSlateBg,
    primaryContainer = NightSlateCard,
    onPrimaryContainer = TextPrimary,
    secondary = CaravanEmerald,
    onSecondary = NightSlateBg,
    secondaryContainer = NightSlateCard,
    onSecondaryContainer = CaravanEmerald,
    tertiary = CaravanAmber,
    background = NightSlateBg,
    onBackground = TextPrimary,
    surface = NightSlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = NightSlateCard,
    onSurfaceVariant = TextSecondary,
    outline = NightSlateBorder,
    error = CaravanCrimson
)

private val LightColorScheme = lightColorScheme(
    primary = CaravanBlueDark,
    onPrimary = LightSurface,
    primaryContainer = LightCard,
    onPrimaryContainer = LightTextPrimary,
    secondary = CaravanEmerald,
    onSecondary = LightSurface,
    secondaryContainer = LightCard,
    onSecondaryContainer = CaravanEmerald,
    tertiary = CaravanAmber,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = CaravanCrimson
)

@Composable
fun CaravanTheme(
    darkTheme: Boolean = true, // Default to sleek automotive night cockpit mode
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    // App UI is LTR-designed; Persian/Arabic system locale must not mirror layout
    // (also breaks MapLibre Compose overlays that use absolute screen pixels).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
