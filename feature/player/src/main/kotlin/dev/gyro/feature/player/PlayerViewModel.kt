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
import dev.gyro.engine.api.PlayerEngine
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
 * Coordinates playback session lifecycle, background continuation, playlist traversal,
 * progress persistence, and auto-resume orchestration.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val orchestrator: PlaybackSessionOrchestrator,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Secondary constructor for existing unit tests
    constructor(
        engine: PlayerEngine,
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

    val engine: PlayerEngine get() = orchestrator.engine
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

    private var initializedUri: Uri? = null

    /**
     * Initializes playback for the requested [uri].
     * Idempotent: repeated calls with the same URI do not reload unless forced.
     */
    fun initialize(uri: Uri?) {
        if (uri == null || initializedUri == uri) return
        initializedUri = uri
        val isAlreadyLoadedAndPlaying = engine.currentUri.value == uri &&
            (engine.isPlaying.value || engine.playbackState.value == dev.gyro.engine.model.PlaybackState.BUFFERING)
        if (!isAlreadyLoadedAndPlaying) {
            val item = playlistManager.items.value.firstOrNull { it.uriString == uri.toString() }
            openUri(
                uri = uri,
                initialWidth = item?.width ?: 0,
                initialHeight = item?.height ?: 0
            )
        }
    }

    /**
     * Handles explicit user-driven exit from the player screen back to home/library.
     * When returning to the home screen, playback is always stopped and audio focus released.
     * (Background audio is reserved for when the application itself is sent to the background / screen locked).
     */
    fun onUserExit() {
        initializedUri = null
        engine.pause()
        engine.abandonAudioFocus()
        engine.detachSurface()
    }

    fun openUri(
        uri: Uri,
        forceReload: Boolean = false,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) {
        orchestrator.openUri(uri, forceReload, displayTitle, initialWidth, initialHeight)
    }

    fun playMediaItem(item: VideoMediaItem) {
        orchestrator.playMediaItem(item)
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        orchestrator.setPlaylistAndPlay(items, item)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video") {
        orchestrator.setSingleAndPlay(uri, displayName)
    }

    fun playNext(): Boolean = orchestrator.playNext()

    fun playPrevious(): Boolean = orchestrator.playPrevious()

    fun saveCurrentProgress() {
        orchestrator.saveCurrentProgress()
    }

    public override fun onCleared() {
        super.onCleared()
        // Cancel transient load work and persist progress
        saveCurrentProgress()
        orchestrator.cancelPendingLoad()
    }
}
