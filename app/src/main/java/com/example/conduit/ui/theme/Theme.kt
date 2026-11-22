package com.example.conduit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = IdeAccent,
    onPrimary = Color.White,
    primaryContainer = IdeAccentSoftDark,
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF818CF8),
    onSecondary = Color.White,
    secondaryContainer = IdeSurfaceBrightDark,
    onSecondaryContainer = IdeTextPrimaryDark,
    background = IdeBgDark,
    onBackground = IdeTextPrimaryDark,
    surface = IdeSurfaceDark,
    onSurface = IdeTextPrimaryDark,
    surfaceVariant = IdeSurfaceElevatedDark,
    onSurfaceVariant = IdeTextSecondaryDark,
    surfaceContainerLowest = Color(0xFF0A0B0E),
    surfaceContainerLow = IdeSurfaceDark,
    surfaceContainer = IdeSurfaceElevatedDark,
    surfaceContainerHigh = IdeSurfaceBrightDark,
    outline = IdeOutlineDark,
    outlineVariant = IdeOutlineDark,
    error = StatusError,
    onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = IdeAccent,
    onPrimary = Color.White,
    primaryContainer = IdeAccentSoftLight,
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    secondaryContainer = IdeSurfaceElevatedLight,
    onSecondaryContainer = IdeTextPrimaryLight,
    background = IdeBgLight,
    onBackground = IdeTextPrimaryLight,
    surface = IdeSurfaceLight,
    onSurface = IdeTextPrimaryLight,
    surfaceVariant = IdeSurfaceElevatedLight,
    onSurfaceVariant = IdeTextSecondaryLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = IdeSurfaceElevatedLight,
    surfaceContainerHigh = Color(0xFFE4E4E7),
    outline = IdeOutlineLight,
    outlineVariant = IdeOutlineLight,
    error = StatusError,
    onError = Color.White
)

@Composable
fun ConduitTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
