package dev.javapad.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AppearanceSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppearanceSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppearanceSettings(),
    )

    fun chooseTheme(theme: AppThemeMode) {
        viewModelScope.launch { repository.setTheme(theme) }
    }

    fun chooseFontSize(sizeSp: Float) {
        viewModelScope.launch { repository.setFontSize(sizeSp) }
    }

    fun chooseTimeout(seconds: Int) {
        viewModelScope.launch { repository.setTimeoutSeconds(seconds) }
    }
}
