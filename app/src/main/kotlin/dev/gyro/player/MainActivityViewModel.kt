package dev.gyro.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state for [MainActivity], following the Now in Android architecture pattern.
 */
sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState
    data class Success(val settings: UserSettings) : MainActivityUiState
}

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val playlistManager: PlaylistManager,
    private val engine: GyroPlayerEngine
) : ViewModel() {

    val uiState: StateFlow<MainActivityUiState> = settingsRepository.settings
        .map { MainActivityUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            initialValue = MainActivityUiState.Loading,
            started = SharingStarted.WhileSubscribed(5_000)
        )

    fun onPlayVideo(item: VideoMediaItem, currentList: List<VideoMediaItem>) {
        val idx = currentList.indexOfFirst { it.uriString == item.uriString }
        playlistManager.setPlaylist(currentList, if (idx >= 0) idx else 0)
    }

    fun handleAppStop(isPip: Boolean): Boolean {
        if (isPip) return false
        val isPlaying = engine.isPlaying.value
        val bgAudio = (uiState.value as? MainActivityUiState.Success)?.settings?.backgroundAudio == true
        if (isPlaying) {
            engine.detachSurface()
            if (bgAudio) {
                return true
            } else {
                engine.pause()
            }
        }
        return false
    }
}
