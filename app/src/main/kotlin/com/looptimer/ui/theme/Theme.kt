package com.looptimer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Work phase accent. */
val WorkColor = Color(0xFFE57373)

/** Break phase accent. */
val BreakColor = Color(0xFF81C784)

/** Alarm / attention accent. */
val AlarmColor = Color(0xFFFFB74D)

private val DarkScheme = darkColorScheme(
    primary = WorkColor,
    secondary = BreakColor,
    tertiary = AlarmColor,
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF2D2D2D),
    surfaceVariant = Color(0xFF3D3D3D),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF9E9E9E)
)

private val LightScheme = lightColorScheme(
    primary = WorkColor,
    secondary = BreakColor,
    tertiary = AlarmColor,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFE0E0E0),
    onBackground = Color(0xFF1E1E1E),
    onSurface = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF757575)
)

/**
 * The app theme.
 *
 * Colours previously lived as `if (isDarkTheme) Color(0xFF…) else Color(0xFF…)` expressions
 * duplicated across three files, while `LoopTimerTheme` was an empty `MaterialTheme {}`. They are
 * now a real colour scheme, so every screen picks them up from `MaterialTheme.colorScheme`.
 */
@Composable
fun LoopTimerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
