package dev.gyro.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.domain.model.PlaybackSessionInfo
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.HandleAppBackgroundUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.ObservePlaybackSessionUseCase
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state for [MainActivity], following the Now in Android architecture pattern.
 * Completely decoupled from [dev.gyro.engine.api.PlayerEngine]; all session operations go through
 * single-method UseCases in `:core:domain`.
 */
sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState
    data class Success(val settings: UserSettings) : MainActivityUiState
}

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    observePlaybackSessionUseCase: ObservePlaybackSessionUseCase,
    private val loadMediaUseCase: LoadMediaUseCase,
    private val handleAppBackgroundUseCase: HandleAppBackgroundUseCase,
    private val endPlaybackSessionUseCase: EndPlaybackSessionUseCase,
) : ViewModel() {

    val uiState: StateFlow<MainActivityUiState> = settingsRepository.settings
        .map { MainActivityUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            initialValue = MainActivityUiState.Loading,
            started = SharingStarted.WhileSubscribed(5_000)
        )

    val sessionInfo: StateFlow<PlaybackSessionInfo> = observePlaybackSessionUseCase()

    fun onPlayVideo(item: VideoMediaItem, currentList: List<VideoMediaItem>) {
        loadMediaUseCase(PlaybackSource.FromPlaylist(playlist = currentList, targetItem = item))
    }

    fun onPlaySingleUri(uri: Uri) {
        loadMediaUseCase(PlaybackSource.Single(uri = uri))
    }

    fun onOpenExternalUri(uri: Uri) {
        loadMediaUseCase(PlaybackSource.Single(uri = uri, skipIfAlreadyActive = true))
    }

    fun handleAppStop(isPip: Boolean) {
        handleAppBackgroundUseCase(isPip)
    }

    fun onPlayerDestinationRemoved() {
        endPlaybackSessionUseCase(SessionEndReason.USER_EXIT)
    }
}
