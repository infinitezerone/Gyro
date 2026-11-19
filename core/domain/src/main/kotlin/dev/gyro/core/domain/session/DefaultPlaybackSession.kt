package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.domain.model.PlaybackFailureReason
import dev.gyro.core.domain.model.PlaybackSessionEvent
import dev.gyro.core.domain.model.PlaybackSessionInfo
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaybackTriggerSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.HandleAppBackgroundUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.ObservePlaybackSessionUseCase
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.VideoScale
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default singleton [PlaybackSession] backed by [PlayerEngine] and `:core:domain` UseCases.
 */
@Singleton
class DefaultPlaybackSession @Inject constructor(
    private val engine: PlayerEngine,
    private val settingsRepository: SettingsRepository,
    private val mpvConfigRepository: MpvConfigRepository,
    private val subtitleRepository: SubtitleRepository,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val playlistStateHolder: PlaylistStateHolder,
    private val loadMediaUseCase: LoadMediaUseCase,
    private val navigatePlaylistUseCase: NavigatePlaylistUseCase,
    private val handleAppBackgroundUseCase: HandleAppBackgroundUseCase,
    private val endPlaybackSessionUseCase: EndPlaybackSessionUseCase,
    observePlaybackSessionUseCase: ObservePlaybackSessionUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) : PlaybackSession {

    init {
        scope.launch {
            var previousDefaultHwdec: String? = null
            combine(
                settingsRepository.settings,
                mpvConfigRepository.parsedOptions,
            ) { settings, options ->
                settings to options
            }.collect { (settings, options) ->
                val defaultHwdecChanged =
                    previousDefaultHwdec != null && previousDefaultHwdec != settings.defaultHwdec
                previousDefaultHwdec = settings.defaultHwdec
                val effectiveSettings = if (defaultHwdecChanged) {
                    sessionStateHolder.onDefaultHwdecChanged(settings.defaultHwdecMode)
                    settings
                } else {
                    sessionStateHolder.updateDefaultHwdecMode(settings.defaultHwdecMode)
                    val effectiveHwdec = sessionStateHolder.resolveEffectiveHwdecKey(settings.defaultHwdec)
                    if (effectiveHwdec == settings.defaultHwdec) {
                        settings
                    } else {
                        settings.copy(defaultHwdec = effectiveHwdec)
                    }
                }
                engine.configure(userOptions = options, settings = effectiveSettings)
            }
        }
    }

    override val state: StateFlow<PlaybackSnapshot> get() = engine.state
    override val diagnostics: StateFlow<PlaybackDiagnostics> get() = engine.diagnostics
    override val isPlaying: StateFlow<Boolean> get() = engine.isPlaying
    override val playbackState: StateFlow<PlaybackState> get() = engine.playbackState
    override val currentUri: StateFlow<Uri?> get() = engine.currentUri
    override val timePosMs: StateFlow<Long> get() = engine.timePosMs
    override val bufferedPosMs: StateFlow<Long> get() = engine.bufferedPosMs
    override val playlist: Playlist get() = playlistStateHolder
    override val render: RenderController get() = engine.render
    override val sessionInfo: StateFlow<PlaybackSessionInfo> = observePlaybackSessionUseCase()
    override val failureReason: StateFlow<PlaybackFailureReason?> get() = sessionStateHolder.failureReason
    override val events: SharedFlow<PlaybackSessionEvent> get() = sessionStateHolder.events
    override val sleepTimerSecondsRemaining: StateFlow<Long> get() = sessionStateHolder.sleepTimerSecondsRemaining

    override fun load(source: PlaybackSource): Job? = loadMediaUseCase(source)

    override fun navigate(step: PlaylistStep, triggerSource: PlaybackTriggerSource?): Boolean =
        navigatePlaylistUseCase(step, triggerSource)

    override fun handleAppBackground(isPip: Boolean): Job? = handleAppBackgroundUseCase(isPip)

    override fun endSession(reason: SessionEndReason) = endPlaybackSessionUseCase(reason)

    override fun setSleepTimer(minutes: Int) {
        sessionStateHolder.setSleepTimer(minutes = minutes, scope = scope) {
            engine.pause()
        }
    }

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun togglePlayPause() = engine.togglePlayPause()
    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    override fun seekRelative(offsetMs: Long) = engine.seekRelative(offsetMs)
    override fun seekToChapter(index: Int) = engine.seekToChapter(index)
    override fun setSpeed(speed: Float) = engine.setSpeed(speed)
    override fun frameStep() = engine.frameStep()
    override fun frameBackStep() = engine.frameBackStep()
    override fun cycleAbLoop() = engine.cycleAbLoop()

    override fun setAbLoopA(seconds: Double) {
        // mpv 只在 A < B 时才会循环。把 A 放到已有 B 之后时交换两点，而不是留下无效区间。
        val bSeconds = engine.state.value.abLoopBSeconds
        if (bSeconds >= 0.0 && seconds >= bSeconds) {
            engine.clearAbLoop()
            engine.setAbLoopA(bSeconds)
            engine.setAbLoopB(seconds)
        } else {
            engine.setAbLoopA(seconds)
        }
    }

    override fun setAbLoopB(seconds: Double) {
        // B 落在 A 之前时同样交换，保证区间始终有效。
        val aSeconds = engine.state.value.abLoopASeconds
        if (aSeconds >= 0.0 && seconds <= aSeconds) {
            engine.clearAbLoop()
            engine.setAbLoopA(seconds)
            engine.setAbLoopB(aSeconds)
        } else {
            engine.setAbLoopB(seconds)
        }
    }

    override fun clearAbLoop() = engine.clearAbLoop()

    override fun toggleHwdec() {
        engine.toggleHwdec()
        sessionStateHolder.onExplicitHwdecSelected(engine.state.value.hwdecMode)
    }

    override fun setHwdec(mode: HwdecMode) {
        sessionStateHolder.onExplicitHwdecSelected(mode)
        engine.setHwdec(mode)
    }

    override fun cycleHwdec(): HwdecMode =
        engine.cycleHwdec().also { sessionStateHolder.onExplicitHwdecSelected(it) }

    override fun selectTrack(type: TrackType, id: Int) = engine.trackManager.selectTrack(type, id)
    override fun selectSecondarySubtitle(id: Int) = engine.trackManager.selectSecondarySubtitle(id)
    override fun addSubtitle(target: String) = engine.trackManager.addSubtitle(target)

    override suspend fun addExternalSubtitle(uri: Uri): Boolean {
        val expectedMedia = currentUri.value
        return subtitleRepository.openSubtitle(uri) { target ->
            if (currentUri.value != expectedMedia) {
                false
            } else {
                engine.trackManager.addSubtitle(target)
                true
            }
        }
    }

    override fun setShader(shader: GlslShader, customPath: String?, customName: String?) =
        engine.shaders.setShader(shader, customPath, customName)

    override fun clearShader() = engine.shaders.clearShader()
    override suspend fun listCustomShaders(): List<File> = engine.shaders.listCustomShaders()
    override suspend fun importAndSetCustomShader(uri: Uri): Result<String> =
        engine.shaders.importAndSetCustomShader(uri)

    override fun setResizeMode(mode: ResizeMode) = engine.geometry.setResizeMode(mode)
    override fun resetZoomAndPan() = engine.geometry.resetZoomAndPan()
    override fun setHdrOutputMode(mode: HdrOutputMode) = engine.geometry.setHdrOutputMode(mode)
    override fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm) =
        engine.geometry.setToneMappingAlgorithm(algorithm)
    override fun setVideoScale(scale: VideoScale) = engine.geometry.setVideoScale(scale)
    override fun setInterpolation(enabled: Boolean) = engine.geometry.setInterpolation(enabled)

    override fun setDeband(enabled: Boolean) = engine.video.setDeband(enabled)
    override fun setBrightness(value: Int) = engine.video.setBrightness(value)
    override fun setContrast(value: Int) = engine.video.setContrast(value)
    override fun setSaturation(value: Int) = engine.video.setSaturation(value)
    override fun resetColors() = engine.video.resetColors()

    override fun setSoftwareVolume(gain: Double) = engine.audio.setSoftwareVolume(gain)
    override fun resetSoftwareGain() = engine.audio.resetSoftwareGain()
    override fun setNightMode(enabled: Boolean) = engine.audio.setNightMode(enabled)
    override fun setStereoDownmix(enabled: Boolean) = engine.audio.setStereoDownmix(enabled)
    override fun setAudioPassthrough(enabled: Boolean) = engine.audio.setAudioPassthrough(enabled)
    override fun setEqualizerGains(gainsDb: List<Double>) = engine.audio.setEqualizerGains(gainsDb)

    override fun setSubtitleDelay(delaySec: Double) = engine.tuning.setSubtitleDelay(delaySec)
    override fun setAudioDelay(delaySec: Double) = engine.tuning.setAudioDelay(delaySec)
    override fun setSubtitleScale(scale: Double) = engine.tuning.setSubtitleScale(scale)
    override fun setSubtitlePosition(percent: Long) = engine.tuning.setSubtitlePosition(percent)
    override fun setSubAssOverride(force: Boolean) = engine.tuning.setSubAssOverride(force)

    override suspend fun queryMediaInfo(
        fallbackTitle: String,
        fallbackUri: String,
        fallbackSize: Long,
    ): MediaInfo = engine.queryMediaInfo(
        fallbackTitle = fallbackTitle,
        fallbackUri = fallbackUri,
        fallbackSize = fallbackSize,
    )
}
