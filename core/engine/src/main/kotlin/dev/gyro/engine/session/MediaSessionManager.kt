package dev.gyro.engine.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages the Media3 [MediaSession], keeping state and metadata synchronized with
 * [GyroPlayerEngine] via [MpvPlayerAdapter], and routing transport controls back to the engine.
 */
class MediaSessionManager(
    private val context: Context,
    val engine: dev.gyro.engine.api.PlayerEngine,
    val playlistManager: PlaylistManager = engine.playlist,
    private val mediaButtonReceiver: PendingIntent? = null,
    private val sessionActivityPendingIntent: PendingIntent? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val uriParser: (String) -> Uri = { Uri.parse(it) },
    looper: Looper? = null
) : MediaSessionControls {
    companion object {
        private const val TAG = "MediaSessionManager"
        const val MEDIA_SESSION_TAG = "GyroMediaSession"

        const val SUPPORTED_ACTIONS: Long =
            android.media.session.PlaybackState.ACTION_PLAY or
            android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
            android.media.session.PlaybackState.ACTION_STOP or
            android.media.session.PlaybackState.ACTION_SEEK_TO or
            android.media.session.PlaybackState.ACTION_FAST_FORWARD or
            android.media.session.PlaybackState.ACTION_REWIND or
            android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
            android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
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
        val durationMs: Long,
        val art: Bitmap? = null
    )

    val playerAdapter: MpvPlayerAdapter? = try {
        val resolvedLooper = looper ?: Looper.myLooper() ?: try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        }
        if (resolvedLooper != null) {
            MpvPlayerAdapter(
                engine = engine,
                playlistManager = playlistManager,
                coroutineScope = coroutineScope,
                onSkipToNext = { skipToNext() },
                onSkipToPrevious = { skipToPrevious() },
                looper = resolvedLooper
            )
        } else {
            null
        }
    } catch (t: Throwable) {
        EngineLog.w(TAG, "MpvPlayerAdapter skipped or failed in current environment: ${t.message}")
        null
    }

    var mediaSession: MediaSession? = null
        private set

    val sessionToken: SessionToken?
        get() = try {
            mediaSession?.token
        } catch (_: Throwable) {
            null
        }

    var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onMetadataChanged: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null
    var onSkipToNext: (() -> Boolean)? = null
    var onSkipToPrevious: (() -> Boolean)? = null

    private var currentArt: Bitmap? = null
    init {
        val adapter = playerAdapter
        if (adapter != null) {
            try {
                val builder = MediaSession.Builder(context, adapter)
                    .setId(MEDIA_SESSION_TAG)
                if (sessionActivityPendingIntent != null) {
                    builder.setSessionActivity(sessionActivityPendingIntent)
                }
                mediaSession = builder.build()
            } catch (t: Throwable) {
                EngineLog.w(TAG, "MediaSession initialization skipped or failed: ${t.message}")
            }
        }

        startObservingEngine()
    }

    private fun startObservingEngine() {
        coroutineScope.launch {
            var lastIsPlaying: Boolean? = null
            var lastTitle: String? = null
            var lastDurationMs: Long? = null

            engine.state.collect { snapshot ->
                val playingChanged = snapshot.isPlaying != lastIsPlaying
                val metadataChanged =
                    snapshot.mediaTitle != lastTitle || snapshot.durationMs != lastDurationMs

                lastIsPlaying = snapshot.isPlaying
                lastTitle = snapshot.mediaTitle
                lastDurationMs = snapshot.durationMs

                if (playingChanged) {
                    onPlaybackStateChanged?.invoke(snapshot.isPlaying)
                }
                if (metadataChanged) {
                    onMetadataChanged?.invoke()
                }
            }
        }
    }

    override fun play() {
        if (playerAdapter != null) {
            playerAdapter.play()
        } else {
            engine.play()
        }
    }

    override fun pause() {
        if (playerAdapter != null) {
            playerAdapter.pause()
        } else {
            engine.pause()
        }
    }

    override fun togglePlayPause() {
        if (playerAdapter != null) {
            if (playerAdapter.isPlaying) {
                pause()
            } else {
                play()
            }
        } else {
            engine.togglePlayPause()
        }
    }

    fun stop() {
        if (playerAdapter != null) {
            playerAdapter.stop()
        } else {
            engine.pause()
        }
        onStopRequested?.invoke()
    }

    fun seekTo(positionMs: Long) {
        if (playerAdapter != null) {
            playerAdapter.seekTo(positionMs)
        } else {
            engine.seekTo(positionMs)
        }
    }

    override fun skipToNext(): Boolean {
        val handled = onSkipToNext?.invoke()
        if (handled == true) return true
        val next = playlistManager.playNext() ?: return false
        engine.load(uriParser(next.uriString), displayTitle = next.displayName)
        return true
    }

    override fun skipToPrevious(): Boolean {
        val handled = onSkipToPrevious?.invoke()
        if (handled == true) return true
        val prev = playlistManager.playPrevious() ?: return false
        engine.load(uriParser(prev.uriString), displayTitle = prev.displayName)
        return true
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
                val handled = skipToNext()
                if (!handled) {
                    togglePlayPause()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val handled = skipToPrevious()
                if (!handled) {
                    engine.seekRelative(-10_000L)
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stop()
                true
            }
            else -> false
        }
    }

    override fun handleKeyEvent(keyEvent: KeyEvent?): Boolean {
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
            engineState == EnginePlaybackState.BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            engineState == EnginePlaybackState.ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }

        return PlaybackStateSnapshot(
            state = state,
            positionMs = pos,
            speed = speed,
            actions = SUPPORTED_ACTIONS
        )
    }

    fun getMediaMetadataSnapshot(): MediaMetadataSnapshot {
        val snapshot = engine.state.value
        return MediaMetadataSnapshot(
            title = snapshot.mediaTitle,
            artist = "Gyro Player",
            durationMs = snapshot.durationMs,
            art = currentArt
        )
    }

    fun updateMetadata(art: Bitmap? = currentArt) {
        currentArt = art
        playerAdapter?.setArtworkBitmap(art)
    }

    fun release() {
        coroutineScope.cancel()
        try {
            mediaSession?.release()
            mediaSession = null
            playerAdapter?.release()
        } catch (t: Throwable) {
            EngineLog.w(TAG, "release MediaSession error: ${t.message}")
        }
    }
}
