package dev.gyro.engine.api

import android.net.Uri
import android.view.Surface
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.controller.AudioController
import dev.gyro.engine.api.controller.GeometryController
import dev.gyro.engine.api.controller.TrackController
import dev.gyro.engine.api.controller.TuningController
import dev.gyro.engine.api.controller.VideoController
import dev.gyro.engine.api.extension.ShaderExtension
import dev.gyro.engine.api.media.MediaItem
import dev.gyro.engine.api.media.Playlist
import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Common playback engine facade interface following Now in Android (NiA) architecture guidelines.
 *
 * Defines the public contract for media playback engines (e.g. mpv, ExoPlayer),
 * decoupling feature modules from specific C++/native libraries and player implementations.
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
    val playlist: Playlist
    val isSurfaceAttached: Boolean
    var isHeadlessMode: Boolean

    val audio: AudioController
    val video: VideoController
    val geometry: GeometryController
    val trackManager: TrackController
    val tuning: TuningController
    val shaders: ShaderExtension

    fun play()
    fun pause()
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

    fun addSubtitle(path: String)

    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun detachSurface(surface: Surface?)
    fun redraw()
    fun requestSurfaceRebuild()

    fun setSpeed(speed: Float)
    fun setResizeMode(mode: ResizeMode)
    fun setVolume(volume: Int)

    // Queue ownership: the engine moves the cursor, callers only read `playlist`. These three
    // commands are the only way the queue changes, so "next video" cannot be implemented twice.
    /** Moves the cursor forward and returns the item to load. Does not load it. */
    fun advanceToNextItem(): VideoMediaItem?

    /** Moves the cursor back and returns the item to load. Does not load it. */
    fun advanceToPreviousItem(): VideoMediaItem?

    /** Moves the cursor to [index] and returns that item. Does not load it. */
    fun selectItemAt(index: Int): VideoMediaItem?

    /** Replaces the queue and positions the cursor at [startIndex]. Does not load anything. */
    fun setPlaylist(items: List<VideoMediaItem>, startIndex: Int)

    /** Replaces the queue with a single item and selects it. Does not load anything. */
    fun setSingleItem(uri: Uri, displayName: String)

    fun cycleAbLoop()
    fun cycleHwdec(): String
    fun toggleHwdec()
    fun setHwdec(target: String)

    fun setHdrOutputMode(mode: String)
    fun setToneMappingAlgorithm(algorithm: String)
    fun setVideoScale(scale: String)
    fun setInterpolation(enabled: Boolean)
    fun setAudioPassthrough(enabled: Boolean)

    fun queryLiveBitrate(): Pair<Long, Long>
    fun queryMediaInfo(
        fallbackTitle: String = "",
        fallbackUri: String = "",
        fallbackSize: Long = 0L
    ): MediaInfo
    fun screenshotToFile(targetFile: File): Boolean

    fun abandonAudioFocus()
    fun release()
}
