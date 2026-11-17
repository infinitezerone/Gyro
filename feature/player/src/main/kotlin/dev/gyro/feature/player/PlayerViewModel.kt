package dev.gyro.feature.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.PowerRepository
import dev.gyro.core.data.repository.ScreenshotRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.model.contentUri
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.model.MediaPalette
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.Playlist
import dev.gyro.feature.player.event.PlaybackTriggerSource
import dev.gyro.feature.player.event.PlayerEvent
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
import kotlin.math.abs

/**
 * UI state container and user event adapter for the Player screen.
 * Delegates all playback session, queue, and media-loading domain logic to single-method UseCases,
 * and delegates all I/O and background work to main-safe Repositories without manual thread switching.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val engine: PlayerEngine,
    settingsRepository: SettingsRepository,
    private val thumbnailRepository: ThumbnailRepository,
    private val subtitleRepository: SubtitleRepository,
    private val screenshotRepository: ScreenshotRepository,
    private val powerRepository: PowerRepository,
    private val loadMediaUseCase: LoadMediaUseCase,
    private val navigatePlaylistUseCase: NavigatePlaylistUseCase,
    private val endPlaybackSessionUseCase: EndPlaybackSessionUseCase,
) : ViewModel() {

    val playlistManager: Playlist get() = engine.playlist

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val gestureConfig: StateFlow<PlayerGestureConfig> = settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = settings.value.gestureConfig,
        )

    val activeUri: StateFlow<Uri?> get() = engine.currentUri

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _screenEntryEpoch = MutableStateFlow(0)
    val screenEntryEpoch: StateFlow<Int> = _screenEntryEpoch.asStateFlow()

    fun notifyScreenEntry() {
        _screenEntryEpoch.value += 1
    }

    private var initializedUri: Uri? = null

    fun initialize(uri: Uri?) {
        if (uri == null || initializedUri == uri) return
        initializedUri = uri
        val source = if (playlistManager.items.value.isEmpty()) {
            PlaybackSource.Single(uri = uri, skipIfAlreadyActive = true)
        } else {
            PlaybackSource.UriItem(uri = uri, skipIfAlreadyActive = true)
        }
        loadMediaUseCase(source)
    }

    fun onUserExit() {
        initializedUri = null
        endPlaybackSessionUseCase(SessionEndReason.USER_EXIT)
    }

    fun openUri(
        uri: Uri,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0,
    ): Job? {
        return loadMediaUseCase(
            PlaybackSource.UriItem(
                uri = uri,
                displayTitle = displayTitle,
                initialWidth = initialWidth,
                initialHeight = initialHeight,
            )
        )
    }

    fun playMediaItem(item: VideoMediaItem): Job? {
        return openUri(
            uri = item.contentUri,
            displayTitle = item.displayName,
            initialWidth = item.width,
            initialHeight = item.height,
        )
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        loadMediaUseCase(PlaybackSource.FromPlaylist(playlist = items, targetItem = item))
    }

    fun setSingleAndPlay(uri: Uri, displayName: String? = null): Job? {
        return loadMediaUseCase(PlaybackSource.Single(uri = uri, displayTitle = displayName))
    }

    fun playNext(): Boolean = navigatePlaylistUseCase(PlaylistStep.Next)

    fun playPrevious(): Boolean = navigatePlaylistUseCase(PlaylistStep.Previous)

    fun playAtIndex(
        index: Int,
        source: PlaybackTriggerSource = PlaybackTriggerSource.USER_PLAYLIST_CLICK,
    ): Boolean {
        val fromIndex = playlistManager.currentIndex.value
        _events.tryEmit(
            PlayerEvent.SwitchInitiated(
                fromIndex = fromIndex,
                toIndex = index,
                source = source,
            )
        )
        val success = navigatePlaylistUseCase(PlaylistStep.AtIndex(index))
        if (success) {
            val item = playlistManager.items.value.getOrNull(index)
            val uri = item?.contentUri ?: activeUri.value ?: Uri.EMPTY
            _events.tryEmit(
                PlayerEvent.SwitchCompleted(
                    index = index,
                    uri = uri,
                )
            )
        }
        return success
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
        engine.addSubtitle(target)
    }

    fun addExternalSubtitle(uri: Uri) {
        val expectedMedia = activeUri.value
        viewModelScope.launch {
            subtitleRepository.openSubtitle(uri) { target ->
                if (activeUri.value != expectedMedia) {
                    false
                } else {
                    engine.addSubtitle(target)
                    true
                }
            }
        }
    }

    fun setCustomShader(uri: Uri) {
        viewModelScope.launch {
            engine.shaders.importAndSetCustomShader(uri)
        }
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
        private const val POSTER_WIDTH = 720
        private const val POSTER_HEIGHT = 1280
        private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L
    }

    fun onPreciseSeek(positionMs: Long, isScrubbing: Boolean) {
        val now = monotonicNowMs()
        if (isScrubbing) {
            val timeDelta = (now - lastPreciseSeekEventUptime).coerceAtLeast(1L)
            val posDelta = abs(positionMs - lastPreciseSeekPosMs)
            val isFirstEvent = (now - lastPreciseSeekEventUptime) > 250L || lastPreciseSeekEventUptime == 0L
            val velocity = if (isFirstEvent) 0.0 else (posDelta.toDouble() / timeDelta.toDouble())

            lastPreciseSeekEventUptime = now
            lastPreciseSeekPosMs = positionMs

            if (velocity > FAST_SCROLL_VELOCITY_THRESHOLD) {
                pendingPreciseSeekJob?.cancel()
                pendingPreciseSeekJob = viewModelScope.launch {
                    delay(80L)
                    lastPreciseSeekTimestamp = monotonicNowMs()
                    engine.seekTo(positionMs)
                }
            } else {
                val elapsed = now - lastPreciseSeekTimestamp
                if (elapsed >= 32L || lastPreciseSeekTimestamp == 0L) {
                    lastPreciseSeekTimestamp = now
                    pendingPreciseSeekJob?.cancel()
                    engine.seekTo(positionMs)
                } else {
                    pendingPreciseSeekJob?.cancel()
                    pendingPreciseSeekJob = viewModelScope.launch {
                        delay(32L - elapsed)
                        lastPreciseSeekTimestamp = monotonicNowMs()
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

    suspend fun getFilmstripThumbnail(timeMs: Long): Bitmap? =
        thumbnailRepository.getThumbnail(activeUri.value, timeMs)

    suspend fun loadPosterBitmap(uri: Uri): Bitmap? =
        thumbnailRepository.getArtwork(uri, POSTER_WIDTH, POSTER_HEIGHT)

    suspend fun extractPalette(bitmap: Bitmap?): MediaPalette =
        thumbnailRepository.extractPalette(bitmap)

    suspend fun saveScreenshot(bitmap: Bitmap, mediaTitle: String, positionMs: Long): Boolean =
        screenshotRepository.saveScreenshot(bitmap, mediaTitle, positionMs)

    suspend fun samplePowerMetrics(): PowerMetrics =
        powerRepository.readPowerMetrics()
}
