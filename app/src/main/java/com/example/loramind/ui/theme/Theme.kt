package com.example.loramind.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── LoraMind Dark Color Scheme (Figma Reference) ──
private val LoraMindDarkScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = DeepGreen,
    onPrimaryContainer = NeonGreen,
    secondary = NeonGreenDim,
    onSecondary = Color.Black,
    background = DeepBlack,
    onBackground = LightGray,
    surface = DarkSlate,
    onSurface = LightGray,
    surfaceVariant = SoftSlate,
    onSurfaceVariant = MediumGray,
    error = StatusRed,
    onError = Color.White,
    outline = GlassBorder,
    outlineVariant = GlassBorder
)

@Composable
fun LoraMindTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LoraMindDarkScheme

    // Make status bar transparent for edge-to-edge dark immersion
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepBlack.toArgb()
            window.navigationBarColor = DeepBlack.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}