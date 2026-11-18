package dev.gyro.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.session.PlaybackSession
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
 * Delegates all playback controls and session teardown to [PlaybackSession] via
 * [SessionPlayerAdapter], letting Media3 own transport notifications and media button routing.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var session: PlaybackSession
    @Inject lateinit var thumbnailRepository: ThumbnailRepository

    @Inject
    @Dispatcher(GyroDispatchers.MainImmediate)
    lateinit var mainDispatcher: CoroutineDispatcher

    @Inject
    @Dispatcher(GyroDispatchers.Default)
    lateinit var defaultDispatcher: CoroutineDispatcher

    companion object {
        private const val TAG = "PlaybackService"
        private const val ARTWORK_SIZE_PX = 512

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
    private var playerAdapter: SessionPlayerAdapter? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created (Media3)")
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        serviceScope = scope

        val adapter = SessionPlayerAdapter(
            session = session,
            mainDispatcher = mainDispatcher,
            compressionDispatcher = defaultDispatcher,
        )
        playerAdapter = adapter

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, adapter)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        scope.launch {
            session.currentUri.collectLatest { uri ->
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
        if (action == ACTION_STOP || action == ACTION_DISMISS) {
            return handleCommand(action = action)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    internal fun handleCommand(
        action: String?,
        onNotificationStop: () -> Unit = { session.endSession(SessionEndReason.SERVICE_STOPPED) },
        stopAndReset: () -> Unit = { stopForegroundAndSelf() },
    ): Int {
        if (action == ACTION_STOP || action == ACTION_DISMISS) {
            onNotificationStop()
            stopAndReset()
            return START_NOT_STICKY
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        Log.i(TAG, "PlaybackService destroyed")
        session.endSession(SessionEndReason.SERVICE_STOPPED)
        mediaSession?.let { activeSession ->
            runCatching { activeSession.release() }
                .onFailure { Log.w(TAG, "release MediaSession failed", it) }
        }
        mediaSession = null
        playerAdapter?.release()
        playerAdapter = null
        serviceScope?.cancel()
        serviceScope = null
        currentThumbnailBitmap = null
        currentThumbnailUri = null
        super.onDestroy()
    }
}
