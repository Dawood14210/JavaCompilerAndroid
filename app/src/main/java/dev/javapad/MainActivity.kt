package dev.javapad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.javapad.settings.SettingsViewModel
import dev.javapad.ui.JavaPadApp
import dev.javapad.ui.theme.JavaPadTheme
import androidx.hilt.navigation.compose.hiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsState()
            JavaPadTheme(mode = settings.theme) {
                SideEffect {
                    val isLight = settings.theme == dev.javapad.settings.AppThemeMode.LIGHT
                    val barColor = androidx.compose.material3.MaterialTheme.colorScheme.background.toArgb()
                    window.statusBarColor = barColor
                    window.navigationBarColor = barColor
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = isLight
                        isAppearanceLightNavigationBars = isLight
                    }
                }
                JavaPadApp(settings = settings, settingsViewModel = settingsViewModel)
            }
        }
    }
}
