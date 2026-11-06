package dev.gyro.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings

    val gestureConfig: StateFlow<PlayerGestureConfig> = settingsRepository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = settingsRepository.settings.value.gestureConfig
        )
}
