package dev.gyro.feature.settings.gestures

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.PlayerGestureConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GestureSettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<GestureSettingsUiState> = repository.settings
        .map { GestureSettingsUiState(config = it.gestureConfig) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = GestureSettingsUiState()
        )

    val gestureConfig: StateFlow<PlayerGestureConfig> = repository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PlayerGestureConfig()
        )

    // --- Vertical Gestures ---

    fun setVolumeGestureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isVolumeEnabled = enabled) }
        }
    }

    fun setBrightnessGestureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isBrightnessEnabled = enabled) }
        }
    }

    fun setInvertSwipeDirections(inverted: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(swapVolumeAndBrightness = inverted) }
        }
    }

    // --- Horizontal & Seeking Gestures ---

    fun setSeekGestureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isSeekEnabled = enabled) }
        }
    }

    fun setDoubleTapSeekEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isDoubleTapSeekEnabled = enabled) }
        }
    }

    fun setDoubleTapSeekSeconds(seconds: Int) {
        viewModelScope.launch {
            repository.setSeekStepSeconds(seconds)
        }
    }

    // --- Playback Controls ---

    fun setDoubleTapPlayPauseEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isDoubleTapPlayPauseEnabled = enabled) }
        }
    }

    fun setLongPressSpeedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isLongPressSpeedEnabled = enabled) }
        }
    }

    fun setLongPressSpeedMultiplier(multiplier: Float) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(longPressSpeed = multiplier) }
        }
    }

    // --- Advanced Gestures ---

    fun setPinchZoomEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateGestureConfig { it.copy(isZoomEnabled = enabled) }
        }
    }

    // --- Reset ---

    fun resetToDefault() {
        viewModelScope.launch {
            repository.resetGestureConfigToDefault()
        }
    }
}
