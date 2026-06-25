package com.aether.memoryeditor.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AetherPrimary,
    onPrimary = Color.White,
    primaryContainer = AetherPrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = AetherSecondary,
    onSecondary = Color.White,
    secondaryContainer = AetherSecondaryDark,
    onSecondaryContainer = Color.White,
    tertiary = AetherAccent,
    onTertiary = Color.Black,
    tertiaryContainer = AetherAccent.copy(alpha = 0.2f),
    onTertiaryContainer = AetherAccent,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorDark,
    onErrorContainer = Color.White,
    outline = DarkSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = AetherPrimary,
    onPrimary = Color.White,
    primaryContainer = AetherPrimaryLight,
    onPrimaryContainer = AetherPrimaryDark,
    secondary = AetherSecondary,
    onSecondary = Color.White,
    secondaryContainer = AetherSecondary.copy(alpha = 0.2f),
    onSecondaryContainer = AetherSecondaryDark,
    tertiary = AetherAccent,
    onTertiary = Color.Black,
    tertiaryContainer = AetherAccent.copy(alpha = 0.2f),
    onTertiaryContainer = AetherAccent.copy(alpha = 0.8f),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = Error,
    onError = Color.White,
    errorContainer = Error.copy(alpha = 0.2f),
    onErrorContainer = ErrorDark,
    outline = LightSurfaceVariant
)

@Composable
fun AetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
