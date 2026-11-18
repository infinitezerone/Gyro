package dev.gyro.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.domain.model.PlaybackSessionInfo
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.session.PlaybackSession
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state for [MainActivity], following the Now in Android architecture pattern.
 * Completely decoupled from playback engine details; all session operations go through
 * [PlaybackSession] in `:core:domain`.
 */
sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState
    data class Success(val settings: UserSettings) : MainActivityUiState
}

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val session: PlaybackSession,
) : ViewModel() {

    val uiState: StateFlow<MainActivityUiState> = settingsRepository.settings
        .map { MainActivityUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            initialValue = MainActivityUiState.Loading,
            started = SharingStarted.WhileSubscribed(5_000),
        )

    val sessionInfo: StateFlow<PlaybackSessionInfo> = session.sessionInfo

    fun onPlayVideo(item: VideoMediaItem, currentList: List<VideoMediaItem>) {
        session.load(PlaybackSource.FromPlaylist(playlist = currentList, targetItem = item))
    }

    fun onPlaySingleUri(uri: Uri) {
        session.load(PlaybackSource.Single(uri = uri))
    }

    fun onOpenExternalUri(uri: Uri) {
        session.load(PlaybackSource.Single(uri = uri, skipIfAlreadyActive = true))
    }

    fun handleAppStop(isPip: Boolean) {
        session.handleAppBackground(isPip)
    }

    fun onPlayerDestinationRemoved() {
        session.endSession(SessionEndReason.USER_EXIT)
    }
}
