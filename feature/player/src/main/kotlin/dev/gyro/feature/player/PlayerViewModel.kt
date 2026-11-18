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
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.MediaPalette
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.model.VideoScale
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.MediaItem
import dev.gyro.engine.api.media.Playlist
import dev.gyro.engine.model.EnginePlaybackException
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackSourceOpenException
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.event.PlaybackFailureReason
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
import java.io.File
import java.io.FileNotFoundException
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

    private val _failureReason = MutableStateFlow<PlaybackFailureReason?>(null)
    val failureReason: StateFlow<PlaybackFailureReason?> = _failureReason.asStateFlow()

    private val _screenEntryEpoch = MutableStateFlow(0)
    val screenEntryEpoch: StateFlow<Int> = _screenEntryEpoch.asStateFlow()

    private var lastHandledError: Throwable? = null
    private var hwdecFallbackAttemptedKey: String? = null
    private var autoFallbackTargetKey: String? = null
    private var lastRequestedHwdec: HwdecMode? = null

    init {
        viewModelScope.launch {
            engine.state.collect { snapshot ->
                onEngineSnapshot(snapshot)
            }
        }
    }

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
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        endPlaybackSessionUseCase(SessionEndReason.USER_EXIT)
    }

    fun openUri(
        uri: Uri,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0,
    ): Job? {
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        engine.geometry.resetZoomAndPan()
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
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        engine.geometry.resetZoomAndPan()
        loadMediaUseCase(PlaybackSource.FromPlaylist(playlist = items, targetItem = item))
    }

    fun setSingleAndPlay(uri: Uri, displayName: String? = null): Job? {
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        engine.geometry.resetZoomAndPan()
        return loadMediaUseCase(PlaybackSource.Single(uri = uri, displayTitle = displayName))
    }

    fun playNext(): Boolean {
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        val success = navigatePlaylistUseCase(PlaylistStep.Next)
        if (success) {
            engine.geometry.resetZoomAndPan()
        }
        return success
    }

    fun playPrevious(): Boolean {
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        val success = navigatePlaylistUseCase(PlaylistStep.Previous)
        if (success) {
            engine.geometry.resetZoomAndPan()
        }
        return success
    }

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
        hwdecFallbackAttemptedKey = null
        restoreAutoFallbackHwdecIfNeeded()
        val success = navigatePlaylistUseCase(PlaylistStep.AtIndex(index))
        if (success) {
            engine.geometry.resetZoomAndPan()
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

    fun seekToChapter(index: Int) {
        engine.seekToChapter(index)
    }

    fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
    }

    fun toggleHwdec() {
        autoFallbackTargetKey = null
        engine.toggleHwdec()
        lastRequestedHwdec = engine.state.value.hwdecMode
    }

    fun setHwdec(mode: HwdecMode) {
        autoFallbackTargetKey = null
        lastRequestedHwdec = mode
        engine.setHwdec(mode)
    }

    fun cycleHwdec(): HwdecMode {
        autoFallbackTargetKey = null
        val next = engine.cycleHwdec()
        lastRequestedHwdec = next
        return next
    }

    fun selectTrack(type: TrackType, id: Int) {
        engine.trackManager.selectTrack(type, id)
    }

    fun selectSubtitleTrack(trackId: Int) = selectTrack(TrackType.SUBTITLE, trackId)

    fun selectSecondarySubtitle(id: Int) {
        engine.trackManager.selectSecondarySubtitle(id)
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

    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null) {
        engine.shaders.setShader(shader, customPath, customName)
    }

    fun clearShader() {
        engine.shaders.clearShader()
    }

    fun setCustomShaderFile(file: File) {
        engine.shaders.setShader(GlslShader.CUSTOM, file.absolutePath, file.nameWithoutExtension)
    }

    fun setCustomShader(uri: Uri) {
        viewModelScope.launch {
            engine.shaders.importAndSetCustomShader(uri)
        }
    }

    fun setDeband(enabled: Boolean) {
        engine.video.setDeband(enabled)
    }

    fun setBrightness(value: Int) {
        engine.video.setBrightness(value)
    }

    fun setContrast(value: Int) {
        engine.video.setContrast(value)
    }

    fun setSaturation(value: Int) {
        engine.video.setSaturation(value)
    }

    fun resetColors() {
        engine.video.resetColors()
    }

    fun setResizeMode(mode: ResizeMode) {
        engine.geometry.setResizeMode(mode)
    }

    fun setHdrOutputMode(mode: HdrOutputMode) {
        engine.setHdrOutputMode(mode)
    }

    fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm) {
        engine.setToneMappingAlgorithm(algorithm)
    }

    fun setVideoScale(scale: VideoScale) {
        engine.setVideoScale(scale)
    }

    fun setInterpolation(enabled: Boolean) {
        engine.setInterpolation(enabled)
    }

    fun setSoftwareVolume(gain: Double) {
        engine.audio.setSoftwareVolume(gain)
    }

    fun setNightMode(enabled: Boolean) {
        engine.audio.setNightMode(enabled)
    }

    fun setEqualizerGains(gainsDb: List<Double>) {
        engine.audio.setEqualizerGains(gainsDb)
    }

    fun setStereoDownmix(enabled: Boolean) {
        engine.audio.setStereoDownmix(enabled)
    }

    fun setAudioPassthrough(enabled: Boolean) {
        engine.setAudioPassthrough(enabled)
    }

    fun setSubtitleDelay(delaySec: Double) {
        engine.tuning.setSubtitleDelay(delaySec)
    }

    fun setAudioDelay(delaySec: Double) {
        engine.tuning.setAudioDelay(delaySec)
    }

    fun setSubtitleScale(scale: Double) {
        engine.tuning.setSubtitleScale(scale)
    }

    fun setSubtitlePosition(percent: Long) {
        engine.tuning.setSubtitlePosition(percent)
    }

    fun setSubAssOverride(force: Boolean) {
        engine.tuning.setSubAssOverride(force)
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

    private fun restoreAutoFallbackHwdecIfNeeded() {
        if (autoFallbackTargetKey != null) {
            autoFallbackTargetKey = null
            lastRequestedHwdec = null
            engine.setHwdec(settings.value.defaultHwdecMode)
        }
    }

    private fun onEngineSnapshot(snapshot: PlaybackSnapshot) {
        val failedTarget = (snapshot.session as? SessionPhase.Failed)?.target?.takeIf { it.isNotBlank() }
        val currentKey = snapshot.currentUri?.toString()?.takeIf { it.isNotBlank() } ?: failedTarget

        if (currentKey != null && autoFallbackTargetKey != null && currentKey != autoFallbackTargetKey) {
            restoreAutoFallbackHwdecIfNeeded()
            hwdecFallbackAttemptedKey = null
        }

        if (snapshot.playbackState != PlaybackState.ERROR || snapshot.error == null) {
            lastHandledError = null
            _failureReason.value = null
            if (snapshot.playbackState == PlaybackState.READY &&
                snapshot.hwdecCurrent.isNotBlank() &&
                autoFallbackTargetKey == null
            ) {
                lastRequestedHwdec = null
            }
            return
        }

        val error = snapshot.error ?: return
        if (error === lastHandledError) return
        lastHandledError = error

        val runtimeHwdecMode = HwdecMode.fromRuntimeValue(snapshot.hwdecCurrent)
        val effectiveHwdecMode = lastRequestedHwdec
            ?: runtimeHwdecMode
            ?: settings.value.defaultHwdecMode
        val effectiveHwdecLabel = snapshot.hwdecCurrent.ifBlank { effectiveHwdecMode.persistedKey }
        val targetUri = snapshot.currentUri ?: activeUri.value
        val reason = classifyPlaybackFailure(error, targetUri, effectiveHwdecLabel)

        val isSourceOpenFailure =
            reason is PlaybackFailureReason.FileNotFound ||
                reason is PlaybackFailureReason.PermissionDenied
        val isHardwareActive = effectiveHwdecMode.isHardware

        if (!isSourceOpenFailure &&
            isHardwareActive &&
            currentKey != null &&
            hwdecFallbackAttemptedKey != currentKey
        ) {
            hwdecFallbackAttemptedKey = currentKey
            autoFallbackTargetKey = currentKey
            lastRequestedHwdec = HwdecMode.SOFTWARE
            _failureReason.value = null
            engine.setHwdec(HwdecMode.SOFTWARE)
            _events.tryEmit(
                PlayerEvent.HwdecAutoFallback(
                    from = effectiveHwdecMode,
                    to = HwdecMode.SOFTWARE,
                )
            )

            val resumePosMs = engine.timePosMs.value.coerceAtLeast(0L)
            val title = snapshot.mediaTitle.ifBlank { null }
            if (targetUri != null) {
                engine.load(
                    uri = targetUri,
                    startPositionMs = resumePosMs,
                    displayTitle = title,
                    initialDurationMs = snapshot.durationMs,
                    initialWidth = snapshot.videoDimensions.width,
                    initialHeight = snapshot.videoDimensions.height,
                )
            } else if (failedTarget != null) {
                engine.load(
                    MediaItem(
                        target = failedTarget,
                        title = title,
                        startPositionMs = resumePosMs,
                        initialDurationMs = snapshot.durationMs,
                        initialWidth = snapshot.videoDimensions.width,
                        initialHeight = snapshot.videoDimensions.height,
                    )
                )
            }
        } else {
            _failureReason.value = reason
            _events.tryEmit(PlayerEvent.ErrorOccurred(reason))
        }
    }

    private fun classifyPlaybackFailure(
        error: Throwable,
        uri: Uri?,
        hwdec: String,
    ): PlaybackFailureReason {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is SecurityException }) {
            return PlaybackFailureReason.PermissionDenied(uri ?: Uri.EMPTY)
        }
        if (chain.any { it is FileNotFoundException || it is PlaybackSourceOpenException }) {
            return PlaybackFailureReason.FileNotFound(uri ?: Uri.EMPTY)
        }
        val isCodecMessage = error.message?.let { msg ->
            msg.contains("codec", ignoreCase = true) || msg.contains("decoder", ignoreCase = true)
        } == true
        if (chain.any { it is EnginePlaybackException }) {
            return if (isCodecMessage) {
                PlaybackFailureReason.CodecCrash(hwdec.ifBlank { "unknown" })
            } else {
                PlaybackFailureReason.DemuxError(error.message ?: "Playback failed")
            }
        }
        return if (isCodecMessage) {
            PlaybackFailureReason.CodecCrash(hwdec.ifBlank { "unknown" })
        } else {
            PlaybackFailureReason.Unknown
        }
    }
}
