package dev.gyro.player.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.domain.session.PlaybackHistoryTracker
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.PlayNextMediaUseCase
import dev.gyro.core.domain.usecase.PlayPreviousMediaUseCase
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.session.MediaSessionControls
import dev.gyro.engine.session.MpvPlayerAdapter
import dev.gyro.player.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground media playback service based on Media3 [MediaSessionService].
 * Automatically handles notifications, lockscreen media card, and system transport integration.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var engine: PlayerEngine
    @Inject lateinit var playNextMediaUseCase: PlayNextMediaUseCase
    @Inject lateinit var playPreviousMediaUseCase: PlayPreviousMediaUseCase
    @Inject lateinit var endPlaybackSessionUseCase: EndPlaybackSessionUseCase
    @Inject lateinit var historyTracker: PlaybackHistoryTracker

    companion object {
        private const val TAG = "PlaybackService"

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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSession? = null
    private var playerAdapter: MpvPlayerAdapter? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created (Media3)")

        val adapter = MpvPlayerAdapter(
            engine = engine,
            onSkipToNext = { playNextMediaUseCase() },
            onSkipToPrevious = { playPreviousMediaUseCase() }
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

        serviceScope.launch {
            engine.currentUri.collect { uri ->
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
            onNotificationStop = { endPlaybackSessionUseCase(requestServiceStop = false) },
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
        if (!engine.isSurfaceAttached) {
            engine.isHeadlessMode = true
        }
        when (action) {
            ACTION_PLAY -> controls?.play() ?: engine.play()
            ACTION_PAUSE -> controls?.pause() ?: engine.pause()
            ACTION_PLAY_PAUSE -> controls?.togglePlayPause() ?: engine.togglePlayPause()
            ACTION_PREV -> controls?.skipToPrevious() ?: playPreviousMediaUseCase()
            ACTION_NEXT -> controls?.skipToNext() ?: playNextMediaUseCase()
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
                            KeyEvent.KEYCODE_MEDIA_NEXT -> playNextMediaUseCase()
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playPreviousMediaUseCase()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun loadThumbnailIfNeeded(uri: Uri?) {
        if (uri == null) {
            currentThumbnailUri = null
            currentThumbnailBitmap = null
            playerAdapter?.setArtworkBitmap(null)
            return
        }
        if (uri == currentThumbnailUri) return
        currentThumbnailUri = uri
        serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadThumbnail(this@PlaybackService, uri)
            }
            if (currentThumbnailUri == uri) {
                currentThumbnailBitmap = bitmap
                playerAdapter?.setArtworkBitmap(bitmap)
            }
        }
    }

    private fun loadThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } else {
                val retriever = MediaMetadataRetriever()
                if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
                    retriever.setDataSource(uri.path)
                } else {
                    retriever.setDataSource(context, uri)
                }
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun stopForegroundAndSelf() {
        engine.isHeadlessMode = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "PlaybackService destroyed")
        endPlaybackSessionUseCase(requestServiceStop = false)
        mediaSession?.let { session ->
            runCatching { session.release() }
                .onFailure { Log.w(TAG, "release MediaSession failed", it) }
        }
        mediaSession = null
        playerAdapter?.release()
        playerAdapter = null
        engine.isHeadlessMode = false
        serviceScope.cancel()
        currentThumbnailBitmap = null
        currentThumbnailUri = null
    }
}
