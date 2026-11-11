package dev.gyro.player.service

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
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.session.MediaSessionControls
import dev.gyro.engine.session.MediaSessionManager
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
    @Inject lateinit var historyRepository: PlaybackHistoryRepository
    @Inject lateinit var mediaSessionManager: MediaSessionManager
    @Inject lateinit var sessionController: dev.gyro.feature.player.session.PlaybackSessionController

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

        /**
         * Tears the service down. Deliberately a bare `stopService`: pushing an ACTION_STOP start
         * request first would re-enter the session exit path (ACTION_STOP -> exit -> stop) while the
         * service instance is still alive, so every teardown would ask for another start.
         * Progress persistence and session teardown live in [onDestroy], which this still triggers.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    private var currentThumbnailUri: Uri? = null
    private var currentThumbnailBitmap: Bitmap? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Sole owner of the system-facing media session. Built in [onCreate], released in [onDestroy],
     * so its registered lifetime is exactly this service instance's lifetime.
     */
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created (Media3)")

        mediaSession = mediaSessionManager.createSession()

        serviceScope.launch {
            engine.currentUri.collect { uri ->
                loadThumbnailIfNeeded(uri)
            }
        }

        mediaSessionManager.onStopRequested = {
            stopForegroundAndSelf()
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
            controls = mediaSessionManager,
            saveProgress = { saveCurrentProgress() },
            stopAndReset = { stopForegroundAndSelf() },
            exitSession = { sessionController.exit() }
        )

        val superSticky = super.onStartCommand(intent, flags, startId)
        return if (action == ACTION_STOP || action == ACTION_DISMISS) sticky else superSticky
    }

    /**
     * Command dispatch for actions and headset keys.
     */
    internal fun handleCommand(
        action: String?,
        keyEvent: KeyEvent?,
        controls: MediaSessionControls?,
        saveProgress: () -> Unit,
        stopAndReset: () -> Unit,
        exitSession: () -> Unit
    ): Int {
        engine.isHeadlessMode = true
        when (action) {
            ACTION_PLAY -> controls?.play()
            ACTION_PAUSE -> controls?.pause()
            ACTION_PLAY_PAUSE -> controls?.togglePlayPause()
            ACTION_PREV -> controls?.skipToPrevious()
            ACTION_NEXT -> controls?.skipToNext()
            ACTION_REWIND -> engine.seekRelative(-10_000L)
            ACTION_FAST_FORWARD -> engine.seekRelative(10_000L)
            ACTION_STOP -> {
                saveProgress()
                engine.pause()
                exitSession()
                stopAndReset()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                saveProgress()
                engine.pause()
                exitSession()
                stopAndReset()
                return START_NOT_STICKY
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                if (keyEvent != null) {
                    controls?.handleKeyEvent(keyEvent)
                }
            }
        }
        return START_STICKY
    }

    private fun saveCurrentProgress() {
        val snapshot = engine.state.value
        val uri = snapshot.currentUri ?: return
        val curPos = engine.timePosMs.value
        val curDur = snapshot.durationMs
        if (curDur > 0L) {
            historyRepository.saveProgress(uri, curPos, curDur)
        }
    }

    private fun loadThumbnailIfNeeded(uri: Uri?) {
        if (uri == null) {
            currentThumbnailUri = null
            currentThumbnailBitmap = null
            mediaSessionManager.updateMetadata(null)
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
                mediaSessionManager.updateMetadata(bitmap)
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
        // The host is the last thing holding a foreground session together: persist the playhead
        // and drop the session flag here, so a teardown started from the notification (stopSelf)
        // and one started from the UI (stopService) converge on the same cleanup.
        saveCurrentProgress()
        sessionController.onServiceDestroyed()
        // Unregister before the process-facing cleanup: until release() returns, the system still
        // routes media buttons and shows the media card for this session.
        mediaSession?.let { session ->
            runCatching { session.release() }
                .onFailure { Log.w(TAG, "release MediaSession failed", it) }
        }
        mediaSession = null
        engine.isHeadlessMode = false
        serviceScope.cancel()
        currentThumbnailBitmap = null
        currentThumbnailUri = null
    }
}
