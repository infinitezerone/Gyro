package dev.gyro.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    val settings: StateFlow<UserSettings> = repository.settings

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
