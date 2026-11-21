package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorContainerCyan = androidx.compose.ui.graphics.Color(0xFF06324A)
private val ColorContainerAmber = androidx.compose.ui.graphics.Color(0xFF3A2906)

private val JarvisColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = ColorContainerCyan,
    onPrimaryContainer = ArcIce,
    secondary = StarkAmber,
    onSecondary = ObsidianVoid,
    secondaryContainer = ColorContainerAmber,
    onSecondaryContainer = StarkGold,
    tertiary = SignalGreen,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = DeepHudNavy,
    onSurface = TextPrimary,
    surfaceVariant = PanelSurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = DeepHudNavy,
    surfaceContainer = PanelSurface,
    surfaceContainerHigh = PanelElevated,
    outline = PanelOutline,
    outlineVariant = PanelOutline,
    error = SignalCrimson,
    onError = ObsidianVoid
)

val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content
    )
}
