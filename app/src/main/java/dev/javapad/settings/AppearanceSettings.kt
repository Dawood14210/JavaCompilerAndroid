package dev.javapad.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.javaPadDataStore by preferencesDataStore(name = "javapad_settings")

/** Appearance modes persist between launches; AMOLED uses a true-black app canvas. */
enum class AppThemeMode {
    LIGHT, DARK, AMOLED;

    companion object {
        fun parse(value: String?): AppThemeMode =
            entries.firstOrNull { it.name == value } ?: LIGHT
    }
}

data class AppearanceSettings(
    val theme: AppThemeMode = AppThemeMode.LIGHT,
    val fontSizeSp: Float = 17f,
    val timeoutSeconds: Int = 30,
)

@Singleton
class AppearanceSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val fontSize = floatPreferencesKey("font_size_sp")
        val timeoutSeconds = intPreferencesKey("timeout_seconds")
    }

    val settings: Flow<AppearanceSettings> = context.javaPadDataStore.data.map { preferences ->
        AppearanceSettings(
            theme = AppThemeMode.parse(preferences[Keys.theme]),
            fontSizeSp = preferences[Keys.fontSize] ?: 17f,
            timeoutSeconds = preferences[Keys.timeoutSeconds] ?: 30,
        )
    }

    suspend fun setTheme(theme: AppThemeMode) {
        context.javaPadDataStore.edit { it[Keys.theme] = theme.name }
    }

    suspend fun setFontSize(sizeSp: Float) {
        context.javaPadDataStore.edit { it[Keys.fontSize] = sizeSp.coerceIn(12f, 28f) }
    }

    suspend fun setTimeoutSeconds(seconds: Int) {
        context.javaPadDataStore.edit { it[Keys.timeoutSeconds] = seconds.coerceIn(5, 300) }
    }
}
