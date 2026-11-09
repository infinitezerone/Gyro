package dev.gyro.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {
    val settings: StateFlow<UserSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setAutoResume(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoResume(enabled)
        }
    }

    fun setBackgroundPlayMode(mode: String) {
        viewModelScope.launch {
            repository.setBackgroundPlayMode(mode)
        }
    }

    fun setSeekStepSeconds(seconds: Int) {
        viewModelScope.launch {
            repository.setSeekStepSeconds(seconds)
        }
    }

    fun setHdrOutputMode(mode: String) {
        viewModelScope.launch {
            repository.setHdrOutputMode(mode)
        }
    }

    fun setDefaultHwdec(hwdec: String) {
        viewModelScope.launch {
            repository.setDefaultHwdec(hwdec)
        }
    }

    fun setSubAutoLoad(enabled: Boolean) {
        viewModelScope.launch {
            repository.setSubAutoLoad(enabled)
        }
    }

    fun setGesturesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setGesturesEnabled(enabled)
        }
    }
}
