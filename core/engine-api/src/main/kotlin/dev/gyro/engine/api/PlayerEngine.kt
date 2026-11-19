package dev.gyro.engine.api

import android.net.Uri
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.UserSettings
import dev.gyro.engine.api.controller.AudioController
import dev.gyro.engine.api.controller.GeometryController
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.api.controller.TrackController
import dev.gyro.engine.api.controller.TuningController
import dev.gyro.engine.api.controller.VideoController
import dev.gyro.engine.api.extension.ShaderExtension
import dev.gyro.engine.api.media.MediaItem
import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * Common playback engine facade interface following Now in Android (NiA) architecture guidelines.
 *
 * Defines the public contract for single-stream media playback engines (e.g. mpv, ExoPlayer),
 * decoupling domain and session orchestration layers from C++/native libraries.
 */
interface PlayerEngine {
    val state: StateFlow<PlaybackSnapshot>

    /**
     * Per-second telemetry (fps, dropped frames, vsync ratio, bitrates).
     *
     * Separate from [state] on purpose: it changes while nothing else does, so folding it into the
     * snapshot would invalidate every consumer of playback state on every counter tick.
     */
    val diagnostics: StateFlow<PlaybackDiagnostics>
    val isPlaying: StateFlow<Boolean>
    val playbackState: StateFlow<PlaybackState>
    val currentUri: StateFlow<Uri?>
    val timePosMs: StateFlow<Long>
    val bufferedPosMs: StateFlow<Long>
    val render: RenderController

    /** Whether media loads may submit directly to native playback while no [android.view.Surface] is attached. */
    val isBackgroundPlaybackAllowed: Boolean

    fun setBackgroundPlaybackAllowed(allowed: Boolean)

    val audio: AudioController
    val video: VideoController
    val geometry: GeometryController
    val trackManager: TrackController
    val tuning: TuningController
    val shaders: ShaderExtension

    fun play()
    fun pause()

    /**
     * Applies updated user `mpv.conf` options and typed GUI [settings] either pre-init or at runtime.
     */
    fun configure(
        userOptions: Map<String, String> = emptyMap(),
        settings: UserSettings = UserSettings(),
    ) {}

    /**
     * Authoritative engine stop.
     * Pauses/stops active media playback, abandons audio focus, detaches the surface, discards
     * pending loads, resets subsystems, and clears [state] back to idle.
     */
    fun stop() {
        pause()
        abandonAudioFocus()
        render.detachSurface()
    }

    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekRelative(offsetMs: Long)
    fun seekToChapter(index: Int)

    fun load(item: MediaItem)
    fun load(
        uri: Uri,
        startPositionMs: Long = 0L,
        displayTitle: String? = null,
        initialDurationMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    )

    fun setSpeed(speed: Float)

    fun cycleAbLoop()
    fun setAbLoopA(seconds: Double)
    fun setAbLoopB(seconds: Double)
    fun clearAbLoop()
    fun frameStep() {}
    fun frameBackStep() {}
    fun cycleHwdec(): HwdecMode
    fun toggleHwdec()
    fun setHwdec(mode: HwdecMode)

    suspend fun queryMediaInfo(
        fallbackTitle: String = "",
        fallbackUri: String = "",
        fallbackSize: Long = 0L
    ): MediaInfo

    fun abandonAudioFocus()
    fun release()
}
