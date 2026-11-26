package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TvDarkColorScheme =
  darkColorScheme(
    primary = TvCyan,
    onPrimary = TvBackground,
    primaryContainer = TvSurfaceFocus,
    onPrimaryContainer = TvTextPrimary,
    secondary = TvCoral,
    onSecondary = TvBackground,
    secondaryContainer = TvSurfaceElevated,
    onSecondaryContainer = TvTextPrimary,
    tertiary = TvEmerald,
    background = TvBackground,
    onBackground = TvTextPrimary,
    surface = TvSurface,
    onSurface = TvTextPrimary,
    surfaceVariant = TvSurfaceElevated,
    onSurfaceVariant = TvTextSecondary,
    outline = TvOutline,
    error = TvCoral,
    onError = TvBackground,
  )

@Composable
fun TvVoiceSearchTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = TvDarkColorScheme, typography = Typography, content = content)
}
