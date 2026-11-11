package dev.gyro.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.feature.player.session.PlaybackSessionController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val engine: PlayerEngine,
    private val session: PlaybackSessionController
) : ViewModel() {

    val uiState: StateFlow<MainActivityUiState> = settingsRepository.settings
        .map { MainActivityUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            initialValue = MainActivityUiState.Loading,
            started = SharingStarted.WhileSubscribed(5_000)
        )

    /**
     * Hosts the foreground service for the whole playback session (across pauses, so the notification
     * can resume it) when background audio is enabled. Started while the UI is still visible, so
     * startForeground runs before the app is backgrounded — starting it in onStop races OEM background
     * freezing and crashes with ForegroundServiceDidNotStartInTimeException.
     */
    val shouldRunPlaybackService: StateFlow<Boolean> =
        combine(session.sessionActive, settingsRepository.settings) { active, settings ->
            active && settings.backgroundAudio
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onPlayVideo(item: VideoMediaItem, currentList: List<VideoMediaItem>) {
        val idx = currentList.indexOfFirst { it.uriString == item.uriString }
        playlistManager.setPlaylist(currentList, if (idx >= 0) idx else 0)
    }

    fun handleAppStop(isPip: Boolean) {
        if (isPip) return
        if (engine.isPlaying.value) {
            engine.detachSurface()
            val bgAudio = (uiState.value as? MainActivityUiState.Success)?.settings?.backgroundAudio == true
            if (!bgAudio) {
                engine.pause()
            }
        }
    }
}
