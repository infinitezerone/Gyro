package dev.gyro.feature.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.PowerRepository
import dev.gyro.core.data.repository.ScreenshotRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.model.contentUri
import dev.gyro.core.domain.session.PlaybackSession
import dev.gyro.core.domain.session.Playlist
import dev.gyro.core.domain.session.PlaylistRepeatMode
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.MediaPalette
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.model.VideoScale
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.event.PlaybackFailureReason
import dev.gyro.feature.player.event.PlaybackTriggerSource
import dev.gyro.feature.player.event.PlayerEvent
import dev.gyro.feature.player.event.toPlayerEvent
import kotlinx.coroutines.CoroutineStart
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
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * UI state container and user event adapter for the Player screen.
 * Consumes solely the application-scoped [PlaybackSession] contract and repositories without
 * holding direct references to the underlying engine or owning playback lifecycle state machines.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val session: PlaybackSession,
    settingsRepository: SettingsRepository,
    private val thumbnailRepository: ThumbnailRepository,
    private val screenshotRepository: ScreenshotRepository,
    private val powerRepository: PowerRepository,
) : ViewModel() {

    val state: StateFlow<PlaybackSnapshot> get() = session.state
    val diagnostics: StateFlow<PlaybackDiagnostics> get() = session.diagnostics
    val isPlaying: StateFlow<Boolean> get() = session.isPlaying
    val playbackState: StateFlow<PlaybackState> get() = session.playbackState
    val activeUri: StateFlow<Uri?> get() = session.currentUri
    val timePosMs: StateFlow<Long> get() = session.timePosMs
    val bufferedPosMs: StateFlow<Long> get() = session.bufferedPosMs
    val playlistManager: Playlist get() = session.playlist
    val playlistRepeatMode: StateFlow<PlaylistRepeatMode> get() = session.playlist.repeatMode
    val renderController: RenderController get() = session.render
    val failureReason: StateFlow<PlaybackFailureReason?> get() = session.failureReason
    val sleepTimerSecondsRemaining: StateFlow<Long> get() = session.sleepTimerSecondsRemaining

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val gestureConfig: StateFlow<PlayerGestureConfig> = settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = settings.value.gestureConfig,
        )

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    val mediaInfo: StateFlow<MediaInfo?> = _mediaInfo.asStateFlow()

    private val _customShaders = MutableStateFlow<List<File>>(emptyList())
    val customShaders: StateFlow<List<File>> = _customShaders.asStateFlow()

    private val _screenEntryEpoch = MutableStateFlow(0)
    val screenEntryEpoch: StateFlow<Int> = _screenEntryEpoch.asStateFlow()

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { event ->
                _events.tryEmit(event.toPlayerEvent())
            }
        }
    }

    fun notifyScreenEntry() {
        _screenEntryEpoch.value += 1
    }

    private var initializedUri: Uri? = null

    fun initialize(uri: Uri?) {
        if (uri == null || (initializedUri == uri && session.currentUri.value == uri)) return
        initializedUri = uri
        val source = if (playlistManager.items.value.isEmpty()) {
            PlaybackSource.Single(uri = uri, skipIfAlreadyActive = true)
        } else {
            PlaybackSource.UriItem(uri = uri, skipIfAlreadyActive = true)
        }
        session.load(source)
    }

    fun onUserExit() {
        initializedUri = null
        session.endSession(SessionEndReason.USER_EXIT)
    }

    fun openUri(
        uri: Uri,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0,
    ): Job? = session.load(
        PlaybackSource.UriItem(
            uri = uri,
            displayTitle = displayTitle,
            initialWidth = initialWidth,
            initialHeight = initialHeight,
        )
    )

    fun playMediaItem(item: VideoMediaItem): Job? = openUri(
        uri = item.contentUri,
        displayTitle = item.displayName,
        initialWidth = item.width,
        initialHeight = item.height,
    )

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem): Job? =
        session.load(PlaybackSource.FromPlaylist(playlist = items, targetItem = item))

    fun setSingleAndPlay(uri: Uri, displayName: String? = null): Job? =
        session.load(PlaybackSource.Single(uri = uri, displayTitle = displayName))

    fun playNext(): Boolean = session.navigate(PlaylistStep.Next)

    fun playPrevious(): Boolean = session.navigate(PlaylistStep.Previous)

    fun playAtIndex(
        index: Int,
        source: PlaybackTriggerSource = PlaybackTriggerSource.USER_PLAYLIST_CLICK,
    ): Boolean = session.navigate(PlaylistStep.AtIndex(index), triggerSource = source)

    fun togglePlayPause() = session.togglePlayPause()

    fun play() = session.play()

    fun pause() = session.pause()

    fun seekTo(positionMs: Long) = session.seekTo(positionMs)

    fun seekRelative(deltaMs: Long) = session.seekRelative(deltaMs)

    fun seekToChapter(index: Int) = session.seekToChapter(index)

    fun setSpeed(speed: Float) = session.setSpeed(speed)

    fun toggleHwdec() = session.toggleHwdec()

    fun setHwdec(mode: HwdecMode) = session.setHwdec(mode)

    fun cycleHwdec(): HwdecMode = session.cycleHwdec()

    fun selectTrack(type: TrackType, id: Int) = session.selectTrack(type, id)

    fun selectSubtitleTrack(trackId: Int) = selectTrack(TrackType.SUBTITLE, trackId)

    fun selectSecondarySubtitle(id: Int) = session.selectSecondarySubtitle(id)

    fun frameStep() = session.frameStep()

    fun frameBackStep() = session.frameBackStep()

    fun addSubtitle(target: String) = session.addSubtitle(target)

    fun addExternalSubtitle(uri: Uri) {
        viewModelScope.launch {
            session.addExternalSubtitle(uri)
        }
    }

    fun refreshMediaInfo(
        fallbackTitle: String = "",
        fallbackUri: String = "",
        fallbackSize: Long = 0L,
    ) {
        viewModelScope.launch {
            _mediaInfo.value = session.queryMediaInfo(
                fallbackTitle = fallbackTitle,
                fallbackUri = fallbackUri,
                fallbackSize = fallbackSize,
            )
        }
    }

    fun refreshCustomShaders() {
        viewModelScope.launch {
            _customShaders.value = session.listCustomShaders()
        }
    }

    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null) =
        session.setShader(shader, customPath, customName)

    fun clearShader() = session.clearShader()

    fun setCustomShaderFile(file: File) =
        session.setShader(GlslShader.CUSTOM, file.absolutePath, file.nameWithoutExtension)

    fun setCustomShader(uri: Uri) {
        viewModelScope.launch {
            session.importAndSetCustomShader(uri)
            refreshCustomShaders()
        }
    }

    fun setDeband(enabled: Boolean) = session.setDeband(enabled)

    fun setBrightness(value: Int) = session.setBrightness(value)

    fun setContrast(value: Int) = session.setContrast(value)

    fun setSaturation(value: Int) = session.setSaturation(value)

    fun resetColors() = session.resetColors()

    fun setResizeMode(mode: ResizeMode) = session.setResizeMode(mode)

    fun setHdrOutputMode(mode: HdrOutputMode) = session.setHdrOutputMode(mode)

    fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm) =
        session.setToneMappingAlgorithm(algorithm)

    fun setVideoScale(scale: VideoScale) = session.setVideoScale(scale)

    fun setInterpolation(enabled: Boolean) = session.setInterpolation(enabled)

    fun setSoftwareVolume(gain: Double) = session.setSoftwareVolume(gain)

    fun setNightMode(enabled: Boolean) = session.setNightMode(enabled)

    fun setEqualizerGains(gainsDb: List<Double>) = session.setEqualizerGains(gainsDb)

    fun setStereoDownmix(enabled: Boolean) = session.setStereoDownmix(enabled)

    fun setAudioPassthrough(enabled: Boolean) = session.setAudioPassthrough(enabled)

    fun setSubtitleDelay(delaySec: Double) = session.setSubtitleDelay(delaySec)

    fun setAudioDelay(delaySec: Double) = session.setAudioDelay(delaySec)

    fun setSubtitleScale(scale: Double) = session.setSubtitleScale(scale)

    fun setSubtitlePosition(percent: Long) = session.setSubtitlePosition(percent)

    fun setSubAssOverride(force: Boolean) = session.setSubAssOverride(force)

    fun resetZoomAndPan() = session.resetZoomAndPan()

    fun resetAudioSoftwareGain() = session.resetSoftwareGain()

    fun cycleAbLoop() = session.cycleAbLoop()

    fun setAbLoopA(seconds: Double) = session.setAbLoopA(seconds)

    fun setAbLoopB(seconds: Double) = session.setAbLoopB(seconds)

    fun clearAbLoop() = session.clearAbLoop()

    fun setSleepTimer(minutes: Int) = session.setSleepTimer(minutes)

    fun setPlaylistRepeatMode(mode: PlaylistRepeatMode) = session.setPlaylistRepeatMode(mode)

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
                    session.seekTo(positionMs)
                }
            } else {
                val elapsed = now - lastPreciseSeekTimestamp
                if (elapsed >= 32L || lastPreciseSeekTimestamp == 0L) {
                    lastPreciseSeekTimestamp = now
                    pendingPreciseSeekJob?.cancel()
                    session.seekTo(positionMs)
                } else {
                    pendingPreciseSeekJob?.cancel()
                    pendingPreciseSeekJob = viewModelScope.launch {
                        delay(32L - elapsed)
                        lastPreciseSeekTimestamp = monotonicNowMs()
                        session.seekTo(positionMs)
                    }
                }
            }
        } else {
            pendingPreciseSeekJob?.cancel()
            lastPreciseSeekTimestamp = now
            lastPreciseSeekEventUptime = 0L
            lastPreciseSeekPosMs = positionMs
            session.seekTo(positionMs)
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
