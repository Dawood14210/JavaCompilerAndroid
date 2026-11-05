package dev.javapad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.javapad.settings.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF625B71),
    background = Color(0xFFF8F7FB),
    surface = Color(0xFFF8F7FB),
    surfaceVariant = Color(0xFFEAE6F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    background = Color(0xFF121116),
    surface = Color(0xFF1A191F),
    surfaceVariant = Color(0xFF29272F),
)

private val AmoledColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF151419),
)

@Composable
fun JavaPadTheme(mode: AppThemeMode, content: @Composable () -> Unit) {
    val colors = when (mode) {
        AppThemeMode.LIGHT -> LightColors
        AppThemeMode.DARK -> DarkColors
        AppThemeMode.AMOLED -> AmoledColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
