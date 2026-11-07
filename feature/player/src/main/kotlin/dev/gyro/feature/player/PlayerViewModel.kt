package dev.gyro.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.feature.player.session.PlaybackSessionOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state container and user event adapter for the Player screen.
 *
 * Delegates playback session lifecycle, background continuation, playlist traversal,
 * progress persistence, and auto-resume orchestration to [PlaybackSessionOrchestrator].
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val orchestrator: PlaybackSessionOrchestrator,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // Secondary constructor for existing unit tests
    constructor(
        engine: GyroPlayerEngine,
        settingsRepository: SettingsRepository,
        historyRepository: PlaybackHistoryRepository,
        playlistManager: PlaylistManager = engine.playlist,
        subtitleRepository: SubtitleRepository,
        uriParser: UriParser,
        mediaRepository: MediaRepository,
        scope: CoroutineScope? = null
    ) : this(
        orchestrator = PlaybackSessionOrchestrator(
            engine = engine,
            settingsRepository = settingsRepository,
            historyRepository = historyRepository,
            subtitleRepository = subtitleRepository,
            mediaRepository = mediaRepository,
            uriParser = uriParser,
            playlist = playlistManager,
            scope = scope
        ),
        settingsRepository = settingsRepository
    )

    val engine: GyroPlayerEngine get() = orchestrator.engine
    val historyRepository: PlaybackHistoryRepository get() = orchestrator.historyRepository
    val playlistManager: PlaylistManager get() = orchestrator.playlist

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val gestureConfig: StateFlow<PlayerGestureConfig> = settingsRepository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PlayerGestureConfig()
        )

    val activeUri: StateFlow<Uri?> get() = orchestrator.activeUri

    /**
     * Bumped on every playback request to key UI resets (e.g. controls visibility).
     */
    private val _screenEntryEpoch = MutableStateFlow(0)
    val screenEntryEpoch: StateFlow<Int> = _screenEntryEpoch.asStateFlow()

    fun notifyScreenEntry() {
        _screenEntryEpoch.value += 1
    }

    fun openUri(uri: Uri, forceReload: Boolean = false, displayTitle: String? = null) {
        orchestrator.openUri(uri, forceReload, displayTitle)
    }

    fun playMediaItem(item: VideoMediaItem) {
        orchestrator.playMediaItem(item)
    }

    fun playNext(): Boolean = orchestrator.playNext()

    fun playPrevious(): Boolean = orchestrator.playPrevious()

    fun saveCurrentProgress() {
        orchestrator.saveCurrentProgress()
    }

    public override fun onCleared() {
        super.onCleared()
        // Only transient load work is cancelled: the orchestrator's periodic checkpoint is a
        // process-lifetime singleton job and must keep running for background playback.
        saveCurrentProgress()
        orchestrator.cancelPendingLoad()
    }
}
