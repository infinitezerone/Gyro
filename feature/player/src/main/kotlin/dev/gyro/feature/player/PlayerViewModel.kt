package dev.gyro.feature.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.Playlist
import dev.gyro.feature.player.domain.PlaybackTriggerSource
import dev.gyro.feature.player.domain.PlayerDomainEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state container and user event adapter for the Player screen.
 * Directly invokes focused domain UseCases.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val engine: PlayerEngine,
    val historyRepository: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    val filmstripThumbnailProvider: FilmstripThumbnailProvider,
    val uriParser: UriParser,
    val loadMediaUseCase: LoadMediaUseCase,
    val navigatePlaylistUseCase: NavigatePlaylistUseCase,
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

    private var loadJob: Job? = null

    /**
     * Handles explicit user-driven exit from the player screen back to home/library.
     * When returning to the home screen, playback is always stopped and audio focus released.
     */
    fun onUserExit() {
        initializedUri = null
        loadJob?.cancel()
        loadJob = null
        filmstripThumbnailProvider.release()
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

    fun playMediaItem(item: VideoMediaItem): Job = navigatePlaylistUseCase.playMediaItem(item)

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        navigatePlaylistUseCase.setPlaylistAndPlay(items, item)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video"): Job {
        return navigatePlaylistUseCase.setSingleAndPlay(uri, displayName)
    }

    fun playNext(): Boolean = navigatePlaylistUseCase.playNext()

    fun playPrevious(): Boolean = navigatePlaylistUseCase.playPrevious()

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
        val success = navigatePlaylistUseCase.playAtIndex(index)
        if (success) {
            val item = playlistManager.items.value.getOrNull(index)
            val uri = item?.let { uriParser.parse(it.uriString) }
                ?: activeUri.value
                ?: uriParser.parse("")
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

    /**
     * Dispatches seek requests during precise seeking outside of Compose UI.
     * When [isScrubbing] is true:
     * - Fast scroll/fling (> 5.0x rate): suppresses real-time engine seeks to avoid pipeline
     *   choking; sets an 80ms resting debounce so it seeks once motion pauses.
     * - Slow micro-scrubbing (<= 5.0x rate): seeks smoothly at ~30fps (~32ms throttling).
     * When [isScrubbing] is false (stopped/released): immediately seeks to final exact position.
     */
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
                // 滑动太快时抑制高频连续 seek，仅在停顿 80ms 后补发
                pendingPreciseSeekJob?.cancel()
                pendingPreciseSeekJob = viewModelScope.launch {
                    delay(80L)
                    engine.seekTo(positionMs)
                }
            } else {
                // 慢速微调时：平滑以 ~30fps (~32ms) 节流向 MPV 派发 seek
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
            // 滑动停定或松手：立即终止防抖并执行终态毫秒级精准 Seek
            pendingPreciseSeekJob?.cancel()
            lastPreciseSeekTimestamp = now
            lastPreciseSeekEventUptime = 0L
            lastPreciseSeekPosMs = positionMs
            engine.seekTo(positionMs)
        }
    }

    suspend fun getFilmstripThumbnail(timeMs: Long): Bitmap? {
        return filmstripThumbnailProvider.getThumbnail(activeUri.value, timeMs)
    }
}
