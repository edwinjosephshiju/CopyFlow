package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ArgonColorScheme = darkColorScheme(
    primary = ArgonViolet,
    onPrimary = ArgonObsidianBg,
    primaryContainer = ArgonVioletDeep,
    onPrimaryContainer = ArgonTextPrimary,
    secondary = ArgonCyan,
    onSecondary = ArgonObsidianBg,
    secondaryContainer = ArgonCyanDim,
    onSecondaryContainer = ArgonCyan,
    tertiary = ArgonEmerald,
    onTertiary = ArgonObsidianBg,
    background = ArgonObsidianBg,
    onBackground = ArgonTextPrimary,
    surface = ArgonIndigoSurface,
    onSurface = ArgonTextPrimary,
    surfaceVariant = ArgonIndigoElevated,
    onSurfaceVariant = ArgonTextSecondary,
    outline = ArgonBorderSubtle,
    outlineVariant = ArgonBorderActive,
    error = ArgonCoral,
)

@Composable
fun CopyFlowTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ArgonColorScheme,
        typography = Typography,
        content = content,
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    CopyFlowTheme(content = content)
}
