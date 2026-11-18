package dev.gyro.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.session.MediaSessionControls
import dev.gyro.engine.session.MpvPlayerAdapter
import dev.gyro.player.MainActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground media playback service based on Media3 [MediaSessionService].
 * Automatically handles notifications, lockscreen media card, and system transport integration.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var engine: PlayerEngine
    @Inject lateinit var navigatePlaylistUseCase: NavigatePlaylistUseCase
    @Inject lateinit var endPlaybackSessionUseCase: EndPlaybackSessionUseCase
    @Inject lateinit var thumbnailRepository: ThumbnailRepository

    @Inject
    @Dispatcher(GyroDispatchers.MainImmediate)
    lateinit var mainDispatcher: CoroutineDispatcher

    companion object {
        private const val TAG = "PlaybackService"
        private const val ARTWORK_SIZE_PX = 512

        const val ACTION_PLAY = "dev.gyro.player.ACTION_PLAY"
        const val ACTION_PAUSE = "dev.gyro.player.ACTION_PAUSE"
        const val ACTION_PLAY_PAUSE = "dev.gyro.player.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "dev.gyro.player.ACTION_PREV"
        const val ACTION_NEXT = "dev.gyro.player.ACTION_NEXT"
        const val ACTION_REWIND = "dev.gyro.player.ACTION_REWIND"
        const val ACTION_FAST_FORWARD = "dev.gyro.player.ACTION_FAST_FORWARD"
        const val ACTION_STOP = "dev.gyro.player.ACTION_STOP"
        const val ACTION_DISMISS = "dev.gyro.player.ACTION_DISMISS"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    private var currentThumbnailUri: Uri? = null
    private var currentThumbnailBitmap: Bitmap? = null
    private var serviceScope: CoroutineScope? = null

    private var mediaSession: MediaSession? = null
    private var playerAdapter: MpvPlayerAdapter? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created (Media3)")
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        serviceScope = scope

        val adapter = MpvPlayerAdapter(
            engine = engine,
            onSkipToNext = { navigatePlaylistUseCase(PlaylistStep.Next) },
            onSkipToPrevious = { navigatePlaylistUseCase(PlaylistStep.Previous) },
            onSelectAtIndex = { index -> navigatePlaylistUseCase(PlaylistStep.AtIndex(index)) },
        )
        playerAdapter = adapter

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, adapter)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        scope.launch {
            engine.currentUri.collectLatest { uri ->
                loadThumbnailIfNeeded(uri)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand: action=$action")

        val keyEvent = if (action == Intent.ACTION_MEDIA_BUTTON) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            null
        }

        val sticky = handleCommand(
            action = action,
            keyEvent = keyEvent,
            onNotificationStop = { endPlaybackSessionUseCase(SessionEndReason.SERVICE_STOPPED) },
            stopAndReset = { stopForegroundAndSelf() }
        )

        val superSticky = super.onStartCommand(intent, flags, startId)
        return if (action == ACTION_STOP || action == ACTION_DISMISS) sticky else superSticky
    }

    internal fun handleCommand(
        action: String?,
        keyEvent: KeyEvent?,
        controls: MediaSessionControls? = null,
        onNotificationStop: () -> Unit,
        stopAndReset: () -> Unit
    ): Int {
        if (action != null && !engine.isSurfaceAttached) {
            engine.isHeadlessMode = true
        }
        when (action) {
            ACTION_PLAY -> controls?.play() ?: engine.play()
            ACTION_PAUSE -> controls?.pause() ?: engine.pause()
            ACTION_PLAY_PAUSE -> controls?.togglePlayPause() ?: engine.togglePlayPause()
            ACTION_PREV -> controls?.skipToPrevious() ?: navigatePlaylistUseCase(PlaylistStep.Previous)
            ACTION_NEXT -> controls?.skipToNext() ?: navigatePlaylistUseCase(PlaylistStep.Next)
            ACTION_REWIND -> engine.seekRelative(-10_000L)
            ACTION_FAST_FORWARD -> engine.seekRelative(10_000L)
            ACTION_STOP, ACTION_DISMISS -> {
                onNotificationStop()
                stopAndReset()
                return START_NOT_STICKY
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                if (keyEvent != null) {
                    val handled = controls?.handleKeyEvent(keyEvent) ?: false
                    if (!handled && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> engine.togglePlayPause()
                            KeyEvent.KEYCODE_MEDIA_PLAY -> engine.play()
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> engine.pause()
                            KeyEvent.KEYCODE_MEDIA_NEXT -> navigatePlaylistUseCase(PlaylistStep.Next)
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> navigatePlaylistUseCase(PlaylistStep.Previous)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun loadThumbnailIfNeeded(uri: Uri?) {
        if (uri == null) {
            currentThumbnailUri = null
            currentThumbnailBitmap = null
            playerAdapter?.setArtworkBitmap(null)
            return
        }
        if (uri == currentThumbnailUri) return
        val bitmap = thumbnailRepository.getArtwork(uri, ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)
        currentThumbnailUri = uri
        currentThumbnailBitmap = bitmap
        playerAdapter?.setArtworkBitmap(bitmap)
    }

    internal fun stopForegroundAndSelf() {
        engine.isHeadlessMode = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "PlaybackService destroyed")
        endPlaybackSessionUseCase(SessionEndReason.SERVICE_STOPPED)
        mediaSession?.let { session ->
            runCatching { session.release() }
                .onFailure { Log.w(TAG, "release MediaSession failed", it) }
        }
        mediaSession = null
        playerAdapter?.release()
        playerAdapter = null
        engine.isHeadlessMode = false
        serviceScope?.cancel()
        serviceScope = null
        currentThumbnailBitmap = null
        currentThumbnailUri = null
        super.onDestroy()
    }
}
