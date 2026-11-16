package dev.gyro.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.core.playback.PlaybackSessionController
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

    val sessionActive: StateFlow<Boolean> get() = session.sessionActive
    val activeUri: StateFlow<Uri?> get() = session.activeUri

    fun onPlayVideo(item: VideoMediaItem, currentList: List<VideoMediaItem>) {
        val idx = currentList.indexOfFirst { it.uriString == item.uriString }
        engine.setPlaylist(currentList, if (idx >= 0) idx else 0)
    }

    /**
     * Handles app-level backgrounding ([Activity.onStop]).
     *
     * Contract:
     * - [UserSettings.backgroundAudio] governs whether playback continues when the entire application
     *   is backgrounded (user presses Home, switches to another app, or screen is turned off).
     * - If background audio is disabled, playback pauses; if enabled, audio continues via [PlaybackService].
     *
     * Contrast with [onPlayerDestinationRemoved]: leaving the player screen within the app terminates
     * the session because there is currently no in-app mini-player host.
     */
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

    /**
     * The player destination left the back stack, i.e. the user is done with it (top-bar back,
     * predictive back, system back, programmatic pop). Navigation state is the authoritative signal;
     * a ViewModel/composable destructor is not, since it also fires for destinations that are merely
     * covered by another screen and never fires at all on process death.
     *
     * Contract:
     * In the absence of an in-app MiniPlayer component, exiting the player screen cleanly terminates
     * playback and the foreground service via [PlaybackSessionController.leavePlayer].
     */
    fun onPlayerDestinationRemoved() {
        session.leavePlayer()
    }
}
