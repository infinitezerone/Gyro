package dev.gyro.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.playback.PlaybackSessionController
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.Playlist
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
 * Session business logic (playlist traversal, subtitles, progress persistence, loading) lives in
 * the app-scoped [PlaybackSessionController] so it survives the screen for background playback;
 * this ViewModel owns only screen-scoped state and forwards playback intents to the controller.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val session: PlaybackSessionController,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val engine: PlayerEngine get() = session.engine
    val historyRepository: PlaybackHistoryRepository get() = session.historyRepository
    val playlistManager: Playlist get() = session.playlist

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val gestureConfig: StateFlow<PlayerGestureConfig> = settingsRepository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PlayerGestureConfig()
        )

    val activeUri: StateFlow<Uri?> get() = session.activeUri

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
        session.leavePlayer()
    }

    fun openUri(
        uri: Uri,
        forceReload: Boolean = false,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) {
        session.openUri(uri, forceReload, displayTitle, initialWidth, initialHeight)
    }

    fun playMediaItem(item: VideoMediaItem) {
        session.playMediaItem(item)
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        session.setPlaylistAndPlay(items, item)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video") {
        session.setSingleAndPlay(uri, displayName)
    }

    fun playNext(): Boolean = session.playNext()

    fun playPrevious(): Boolean = session.playPrevious()

    fun playAtIndex(index: Int): Boolean = session.playAtIndex(index)

    fun saveCurrentProgress() {
        session.saveCurrentProgress()
    }

    public override fun onCleared() {
        super.onCleared()
        // Nothing here may drive playback state. `onCleared` is a destructor for this instance, not
        // a "user left the screen" signal: it is never called when the process is killed, its timing
        // is not part of the contract, and reacting to it would turn a lifecycle callback into a
        // business command. Leaving the player is signalled by the navigation layer
        // (MainActivity watches the back stack) and by the explicit exit callbacks.
        // Only clean up what this ViewModel owns: persist the playhead and cancel this screen's
        // transient load work. The session's collectors intentionally outlive the ViewModel for
        // background playback, so they are not cancelled here.
        saveCurrentProgress()
        session.cancelPendingLoad()
    }
}
