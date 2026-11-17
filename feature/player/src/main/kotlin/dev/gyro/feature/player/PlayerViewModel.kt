package dev.gyro.feature.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.domain.model.contentUri
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.PlayMediaAtIndexUseCase
import dev.gyro.core.domain.usecase.PlayNextMediaUseCase
import dev.gyro.core.domain.usecase.PlayPreviousMediaUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.domain.usecase.SetPlaylistAndPlayUseCase
import dev.gyro.core.domain.usecase.SetSingleMediaAndPlayUseCase
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.Playlist
import dev.gyro.feature.player.domain.PlaybackTriggerSource
import dev.gyro.feature.player.domain.PlayerDomainEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state container and user event adapter for the Player screen.
 * Directly invokes focused domain UseCases and encapsulates playback controls.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val engine: PlayerEngine,
    val historyRepository: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    val thumbnailRepository: ThumbnailRepository,
    val loadMediaUseCase: LoadMediaUseCase,
    val playNextMediaUseCase: PlayNextMediaUseCase,
    val playPreviousMediaUseCase: PlayPreviousMediaUseCase,
    val playMediaAtIndexUseCase: PlayMediaAtIndexUseCase,
    val setPlaylistAndPlayUseCase: SetPlaylistAndPlayUseCase,
    val setSingleMediaAndPlayUseCase: SetSingleMediaAndPlayUseCase,
    val endPlaybackSessionUseCase: EndPlaybackSessionUseCase,
    val saveProgressUseCase: SavePlaybackProgressUseCase,
) : ViewModel() {

    val playlistManager: Playlist get() = engine.playlist

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val gestureConfig: StateFlow<PlayerGestureConfig> = settingsRepository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PlayerGestureConfig()
        )

    val activeUri: StateFlow<Uri?> get() = engine.currentUri

    private val _domainEvents = MutableSharedFlow<PlayerDomainEvent>(extraBufferCapacity = 64)
    val domainEvents: SharedFlow<PlayerDomainEvent> = _domainEvents.asSharedFlow()

    private val _screenEntryEpoch = MutableStateFlow(0)
    val screenEntryEpoch: StateFlow<Int> = _screenEntryEpoch.asStateFlow()

    fun notifyScreenEntry() {
        _screenEntryEpoch.value += 1
    }

    private var initializedUri: Uri? = null

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

    private var loadJob: Job? = null

    fun onUserExit() {
        initializedUri = null
        loadJob?.cancel()
        loadJob = null
        thumbnailRepository.release()
        endPlaybackSessionUseCase(requestServiceStop = true)
    }

    fun openUri(
        uri: Uri,
        forceReload: Boolean = false,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ): Job {
        loadJob?.cancel()
        val job = viewModelScope.launch {
            loadMediaUseCase(
                uri = uri,
                displayTitle = displayTitle,
                initialWidth = initialWidth,
                initialHeight = initialHeight
            )
        }
        loadJob = job
        return job
    }

    fun playMediaItem(item: VideoMediaItem): Job {
        return openUri(
            uri = item.contentUri,
            displayTitle = item.displayName,
            initialWidth = item.width,
            initialHeight = item.height
        )
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        setPlaylistAndPlayUseCase(items, item)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video"): Job {
        return setSingleMediaAndPlayUseCase(uri, displayName)
    }

    fun playNext(): Boolean = playNextMediaUseCase()

    fun playPrevious(): Boolean = playPreviousMediaUseCase()

    fun playAtIndex(
        index: Int,
        source: PlaybackTriggerSource = PlaybackTriggerSource.USER_PLAYLIST_CLICK
    ): Boolean {
        val fromIndex = playlistManager.currentIndex.value
        _domainEvents.tryEmit(
            PlayerDomainEvent.SwitchInitiated(
                fromIndex = fromIndex,
                toIndex = index,
                source = source
            )
        )
        val success = playMediaAtIndexUseCase(index)
        if (success) {
            val item = playlistManager.items.value.getOrNull(index)
            val uri = item?.contentUri ?: activeUri.value ?: Uri.EMPTY
            _domainEvents.tryEmit(
                PlayerDomainEvent.SwitchCompleted(
                    index = index,
                    uri = uri
                )
            )
        }
        return success
    }

    fun saveCurrentProgress() {
        saveProgressUseCase()
    }

    fun togglePlayPause() {
        engine.togglePlayPause()
    }

    fun play() {
        engine.play()
    }

    fun pause() {
        engine.pause()
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
    }

    fun seekRelative(deltaMs: Long) {
        engine.seekRelative(deltaMs)
    }

    fun selectSubtitleTrack(trackId: Int) {
        engine.trackManager.selectTrack("sub", trackId)
    }

    fun frameStep() {
        engine.frameStep()
    }

    fun frameBackStep() {
        engine.frameBackStep()
    }

    fun addSubtitle(target: String) {
        engine.trackManager.addSubtitle(target)
    }

    fun setCustomShader(uri: Uri) {
        engine.shaders.importAndSetCustomShader(uri)
    }

    fun resetZoomAndPan() {
        engine.geometry.resetZoomAndPan()
    }

    fun resetAudioSoftwareGain() {
        engine.audio.resetSoftwareGain()
    }

    fun cycleAbLoop() {
        engine.cycleAbLoop()
    }

    fun clearAbLoop() {
        engine.clearAbLoop()
    }

    private var sleepTimerJob: Job? = null
    private val _sleepTimerSecondsRemaining = MutableStateFlow(0L)
    val sleepTimerSecondsRemaining: StateFlow<Long> = _sleepTimerSecondsRemaining.asStateFlow()

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerSecondsRemaining.value = 0L
            return
        }
        _sleepTimerSecondsRemaining.value = minutes * 60L
        sleepTimerJob = viewModelScope.launch {
            while (_sleepTimerSecondsRemaining.value > 0) {
                delay(1000L)
                _sleepTimerSecondsRemaining.value -= 1
            }
            engine.pause()
        }
    }

    private var pendingPreciseSeekJob: Job? = null
    private var lastPreciseSeekTimestamp = 0L
    private var lastPreciseSeekPosMs = 0L
    private var lastPreciseSeekEventUptime = 0L

    companion object {
        private const val FAST_SCROLL_VELOCITY_THRESHOLD = 5.0
    }

    fun onPreciseSeek(positionMs: Long, isScrubbing: Boolean) {
        val now = System.currentTimeMillis()
        if (isScrubbing) {
            val timeDelta = (now - lastPreciseSeekEventUptime).coerceAtLeast(1L)
            val posDelta = kotlin.math.abs(positionMs - lastPreciseSeekPosMs)
            val isFirstEvent = (now - lastPreciseSeekEventUptime) > 250L || lastPreciseSeekEventUptime == 0L
            val velocity = if (isFirstEvent) 0.0 else (posDelta.toDouble() / timeDelta.toDouble())

            lastPreciseSeekEventUptime = now
            lastPreciseSeekPosMs = positionMs

            if (velocity > FAST_SCROLL_VELOCITY_THRESHOLD) {
                pendingPreciseSeekJob?.cancel()
                pendingPreciseSeekJob = viewModelScope.launch {
                    delay(80L)
                    engine.seekTo(positionMs)
                }
            } else {
                if (now - lastPreciseSeekTimestamp >= 32L) {
                    lastPreciseSeekTimestamp = now
                    pendingPreciseSeekJob?.cancel()
                    engine.seekTo(positionMs)
                } else {
                    pendingPreciseSeekJob?.cancel()
                    pendingPreciseSeekJob = viewModelScope.launch {
                        delay(32L - (now - lastPreciseSeekTimestamp))
                        engine.seekTo(positionMs)
                    }
                }
            }
        } else {
            pendingPreciseSeekJob?.cancel()
            lastPreciseSeekTimestamp = now
            lastPreciseSeekEventUptime = 0L
            lastPreciseSeekPosMs = positionMs
            engine.seekTo(positionMs)
        }
    }

    suspend fun getFilmstripThumbnail(timeMs: Long): Bitmap? {
        return thumbnailRepository.getThumbnail(activeUri.value, timeMs)
    }
}
