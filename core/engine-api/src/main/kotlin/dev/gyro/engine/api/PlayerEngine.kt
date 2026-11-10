package dev.gyro.engine.api

import android.net.Uri
import android.view.Surface
import dev.gyro.engine.media.MediaItem
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Common playback engine facade interface following Now in Android (NiA) architecture guidelines.
 *
 * Defines the public contract for media playback engines (e.g. mpv, ExoPlayer),
 * decoupling feature modules from specific C++/native libraries and player implementations.
 */
interface PlayerEngine {
    val state: StateFlow<PlaybackSnapshot>
    val isPlaying: StateFlow<Boolean>
    val playbackState: StateFlow<PlaybackState>
    val currentUri: StateFlow<Uri?>
    val timePosMs: StateFlow<Long>
    val bufferedPosMs: StateFlow<Long>
    val playlist: PlaylistManager

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekRelative(offsetMs: Long)

    fun load(item: MediaItem)
    fun load(
        uri: Uri,
        startPositionMs: Long = 0L,
        displayTitle: String? = null,
        initialDurationMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    )

    fun attachSurface(surface: Surface)
    fun detachSurface()

    fun setSpeed(speed: Float)
    fun setResizeMode(mode: ResizeMode)
    fun setVolume(volume: Int)

    fun abandonAudioFocus()
    fun release()

    /**
     * Obtains an optional engine extension (e.g. [dev.gyro.engine.api.extension.ShaderExtension],
     * [dev.gyro.engine.api.extension.MpvConfigExtension]), or returns null if the current
     * engine implementation does not support it.
     */
    fun <T : EngineExtension> getExtension(extensionClass: Class<T>): T? = null
}
