package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.domain.model.PlaybackFailureReason
import dev.gyro.core.domain.model.PlaybackSessionEvent
import dev.gyro.core.domain.model.PlaybackSessionInfo
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaybackTriggerSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.VideoScale
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Authoritative application-scoped playback session contract.
 *
 * Unifies single-stream [dev.gyro.engine.api.PlayerEngine] control, domain queue ownership
 * ([Playlist]), progress persistence, companion subtitle loading, hardware-decode auto-fallback,
 * and session-scoped sleep timing so foreground UI and background `MediaSessionService` act as
 * equal clients over the exact same session state machine.
 */
interface PlaybackSession {
    val state: StateFlow<PlaybackSnapshot>
    val diagnostics: StateFlow<PlaybackDiagnostics>
    val isPlaying: StateFlow<Boolean>
    val playbackState: StateFlow<PlaybackState>
    val currentUri: StateFlow<Uri?>
    val timePosMs: StateFlow<Long>
    val bufferedPosMs: StateFlow<Long>
    val playlist: Playlist
    val render: RenderController
    val sessionInfo: StateFlow<PlaybackSessionInfo>
    val failureReason: StateFlow<PlaybackFailureReason?>
    val events: SharedFlow<PlaybackSessionEvent>
    val sleepTimerSecondsRemaining: StateFlow<Long>

    fun load(source: PlaybackSource): Job?
    fun navigate(step: PlaylistStep, triggerSource: PlaybackTriggerSource? = null): Boolean
    fun handleAppBackground(isPip: Boolean): Job?
    fun handlePipDismissed()
    fun endSession(reason: SessionEndReason = SessionEndReason.USER_EXIT)
    fun setSleepTimer(minutes: Int)
    fun setPlaylistRepeatMode(mode: PlaylistRepeatMode)

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekRelative(offsetMs: Long)
    fun seekToChapter(index: Int)
    fun setSpeed(speed: Float)
    fun frameStep()
    fun frameBackStep()
    fun cycleAbLoop()
    fun setAbLoopA(seconds: Double)
    fun setAbLoopB(seconds: Double)
    fun clearAbLoop()

    fun toggleHwdec()
    fun setHwdec(mode: HwdecMode)
    fun cycleHwdec(): HwdecMode

    fun selectTrack(type: TrackType, id: Int)
    fun selectSecondarySubtitle(id: Int)
    fun addSubtitle(target: String)
    suspend fun addExternalSubtitle(uri: Uri): Boolean

    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null)
    fun clearShader()
    suspend fun listCustomShaders(): List<File>
    suspend fun importAndSetCustomShader(uri: Uri): Result<String>

    fun setResizeMode(mode: ResizeMode)
    fun resetZoomAndPan()
    fun setHdrOutputMode(mode: HdrOutputMode)
    fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm)
    fun setVideoScale(scale: VideoScale)
    fun setInterpolation(enabled: Boolean)

    fun setDeband(enabled: Boolean)
    fun setBrightness(value: Int)
    fun setContrast(value: Int)
    fun setSaturation(value: Int)
    fun resetColors()

    fun setSoftwareVolume(gain: Double)
    fun resetSoftwareGain()
    fun setNightMode(enabled: Boolean)
    fun setStereoDownmix(enabled: Boolean)
    fun setAudioPassthrough(enabled: Boolean)
    fun setEqualizerGains(gainsDb: List<Double>)

    fun setSubtitleDelay(delaySec: Double)
    fun setAudioDelay(delaySec: Double)
    fun setSubtitleScale(scale: Double)
    fun setSubtitlePosition(percent: Long)
    fun setSubAssOverride(force: Boolean)

    suspend fun queryMediaInfo(
        fallbackTitle: String = "",
        fallbackUri: String = "",
        fallbackSize: Long = 0L,
    ): MediaInfo
}
