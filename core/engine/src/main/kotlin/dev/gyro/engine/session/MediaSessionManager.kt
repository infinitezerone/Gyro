package dev.gyro.engine.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages the Android platform [MediaSession], keeping [PlaybackState] and [MediaMetadata]
 * synchronized with [GyroPlayerEngine] and [PlaylistManager], and routing transport/headset
 * controls back to the engine.
 */
class MediaSessionManager(
    private val context: Context,
    val engine: GyroPlayerEngine,
    val playlistManager: PlaylistManager,
    private val mediaButtonReceiver: PendingIntent? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val uriParser: (String) -> Uri = { Uri.parse(it) }
) {
    companion object {
        private const val TAG = "MediaSessionManager"
        const val MEDIA_SESSION_TAG = "GyroMediaSession"

        const val SUPPORTED_ACTIONS: Long =
            PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }

    data class PlaybackStateSnapshot(
        val state: Int,
        val positionMs: Long,
        val speed: Float,
        val actions: Long
    )

    data class MediaMetadataSnapshot(
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    private var mediaSession: MediaSession? = null

    val sessionToken: MediaSession.Token?
        get() = try {
            mediaSession?.sessionToken
        } catch (_: Throwable) {
            null
        }

    var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null
    var onPlayMediaItemRequested: ((VideoMediaItem) -> Unit)? = null

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            this@MediaSessionManager.play()
        }

        override fun onPause() {
            this@MediaSessionManager.pause()
        }

        override fun onStop() {
            this@MediaSessionManager.stop()
        }

        override fun onSeekTo(pos: Long) {
            this@MediaSessionManager.seekTo(pos)
        }

        override fun onSkipToNext() {
            this@MediaSessionManager.skipToNext()
        }

        override fun onSkipToPrevious() {
            this@MediaSessionManager.skipToPrevious()
        }

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val keyEvent = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (handleKeyEvent(keyEvent)) {
                return true
            }
            return try {
                super.onMediaButtonEvent(mediaButtonIntent)
            } catch (_: Throwable) {
                false
            }
        }
    }

    init {
        try {
            val session = MediaSession(context, MEDIA_SESSION_TAG)
            session.setCallback(sessionCallback)
            mediaButtonReceiver?.let {
                @Suppress("DEPRECATION")
                session.setMediaButtonReceiver(it)
            }
            session.isActive = true
            mediaSession = session
        } catch (t: Throwable) {
            EngineLog.w(TAG, "MediaSession initialization skipped or failed: ${t.message}")
        }

        startObservingEngine()
    }

    private fun startObservingEngine() {
        coroutineScope.launch {
            launch {
                var lastIsPlaying: Boolean? = null
                var lastState: EnginePlaybackState? = null
                var lastSpeed: Float? = null
                var lastTitle: String? = null
                var lastDurationMs: Long? = null

                engine.state.collect { snapshot ->
                    val playingChanged = snapshot.isPlaying != lastIsPlaying
                    val stateChanged =
                        playingChanged || snapshot.playbackState != lastState || snapshot.speed != lastSpeed
                    val metadataChanged =
                        snapshot.mediaTitle != lastTitle || snapshot.durationMs != lastDurationMs

                    lastIsPlaying = snapshot.isPlaying
                    lastState = snapshot.playbackState
                    lastSpeed = snapshot.speed
                    lastTitle = snapshot.mediaTitle
                    lastDurationMs = snapshot.durationMs

                    if (stateChanged) {
                        updatePlaybackState()
                    }
                    if (playingChanged) {
                        onPlaybackStateChanged?.invoke(snapshot.isPlaying)
                    }
                    if (metadataChanged) {
                        updateMetadata()
                    }
                }
            }
            var lastReportedPos = 0L
            launch {
                engine.timePosMs.collect { pos ->
                    // Android's PlaybackState automatically extrapolates playback position based on
                    // updateTime and speed when STATE_PLAYING. Updating on every 50ms progress tick
                    // floods system_server Binder IPC. Only update on large position discontinuities (seeks)
                    // or periodic low-frequency sync.
                    if (kotlin.math.abs(pos - lastReportedPos) >= 2000L) {
                        lastReportedPos = pos
                        updatePlaybackState()
                    }
                }
            }
        }
    }

    fun play() {
        engine.play()
    }

    fun pause() {
        engine.pause()
    }

    fun togglePlayPause() {
        engine.togglePlayPause()
    }

    fun stop() {
        engine.pause()
        onStopRequested?.invoke()
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
    }

    private fun dispatchMediaItem(item: VideoMediaItem) {
        val delegate = onPlayMediaItemRequested ?: playlistManager.mediaItemLauncher
        if (delegate != null) {
            delegate(item)
        } else {
            engine.load(uriParser(item.uriString), displayTitle = item.displayName)
        }
    }

    fun skipToNext(): Boolean {
        val next = playlistManager.playNext()
        return if (next != null) {
            dispatchMediaItem(next)
            true
        } else {
            false
        }
    }

    fun skipToPrevious(): Boolean {
        val prev = playlistManager.playPrevious()
        return if (prev != null) {
            dispatchMediaItem(prev)
            true
        } else {
            false
        }
    }

    fun handleKeyCode(action: Int, keyCode: Int): Boolean {
        if (action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                play()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                pause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                skipToNext()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                skipToPrevious()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stop()
                true
            }
            else -> false
        }
    }

    fun handleKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (keyEvent == null) return false
        return handleKeyCode(keyEvent.action, keyEvent.keyCode)
    }

    fun getPlaybackStateSnapshot(): PlaybackStateSnapshot {
        val snapshot = engine.state.value
        val isPlaying = snapshot.isPlaying
        val pos = engine.timePosMs.value
        val speed = snapshot.speed
        val engineState = snapshot.playbackState

        val state = when {
            engineState == EnginePlaybackState.BUFFERING -> PlaybackState.STATE_BUFFERING
            isPlaying -> PlaybackState.STATE_PLAYING
            engineState == EnginePlaybackState.ENDED -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }

        return PlaybackStateSnapshot(
            state = state,
            positionMs = pos,
            speed = speed,
            actions = SUPPORTED_ACTIONS
        )
    }

    fun buildPlaybackState(): PlaybackState? {
        val snapshot = getPlaybackStateSnapshot()
        return try {
            PlaybackState.Builder()
                .setActions(snapshot.actions)
                .setState(snapshot.state, snapshot.positionMs, snapshot.speed, SystemClock.elapsedRealtime())
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    fun updatePlaybackState(): PlaybackState? {
        return try {
            val state = buildPlaybackState()
            if (state != null) {
                mediaSession?.setPlaybackState(state)
            }
            state
        } catch (t: Throwable) {
            EngineLog.w(TAG, "updatePlaybackState error: ${t.message}")
            null
        }
    }

    fun getMediaMetadataSnapshot(): MediaMetadataSnapshot {
        val snapshot = engine.state.value
        return MediaMetadataSnapshot(
            title = snapshot.mediaTitle,
            artist = "Gyro Player",
            durationMs = snapshot.durationMs
        )
    }

    fun buildMediaMetadata(): MediaMetadata? {
        val snapshot = getMediaMetadataSnapshot()
        return try {
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    fun updateMetadata(): MediaMetadata? {
        return try {
            val metadata = buildMediaMetadata()
            if (metadata != null) {
                mediaSession?.setMetadata(metadata)
            }
            metadata
        } catch (t: Throwable) {
            EngineLog.w(TAG, "updateMetadata error: ${t.message}")
            null
        }
    }

    fun release() {
        coroutineScope.cancel()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (t: Throwable) {
            EngineLog.w(TAG, "release MediaSession error: ${t.message}")
        }
    }
}
