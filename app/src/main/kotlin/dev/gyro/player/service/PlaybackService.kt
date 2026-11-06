package dev.gyro.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.designsystem.R
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.playlist.PlaylistManager
import dev.gyro.engine.session.MediaSessionManager
import dev.gyro.player.MainActivity
import javax.inject.Inject

/**
 * Foreground service for background audio playback with lockscreen & notification media card.
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var engine: GyroPlayerEngine
    @Inject lateinit var playlistManager: PlaylistManager

    companion object {
        private const val TAG = "PlaybackService"

        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "dev.gyro.player.ACTION_PLAY"
        const val ACTION_PAUSE = "dev.gyro.player.ACTION_PAUSE"
        const val ACTION_PLAY_PAUSE = "dev.gyro.player.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "dev.gyro.player.ACTION_PREV"
        const val ACTION_NEXT = "dev.gyro.player.ACTION_NEXT"
        const val ACTION_STOP = "dev.gyro.player.ACTION_STOP"
        const val ACTION_DISMISS = "dev.gyro.player.ACTION_DISMISS"

        @Volatile
        var isRunning: Boolean = false
            internal set

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun dismiss(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_DISMISS
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
        val mediaSessionManager: MediaSessionManager? get() = this@PlaybackService.mediaSessionManager
    }

    private val binder = LocalBinder()
    var mediaSessionManager: MediaSessionManager? = null
        private set

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "PlaybackService created")
        createNotificationChannel()

        setupMediaSessionManager(engine, playlistManager)
    }

    fun setupMediaSessionManager(engine: GyroPlayerEngine, playlistManager: PlaylistManager) {
        mediaSessionManager?.release()
        val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, PlaybackService::class.java)
        val buttonPendingIntent = PendingIntent.getService(
            this,
            0,
            buttonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSessionManager = MediaSessionManager(
            context = this,
            engine = engine,
            playlistManager = playlistManager,
            mediaButtonReceiver = buttonPendingIntent
        ).apply {
            onPlaybackStateChanged = { _ ->
                updateNotification()
            }
            onStopRequested = {
                stopForegroundAndSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand: action=$action")

        // Lazy-connect engine if not yet attached
        if (mediaSessionManager == null) {
            setupMediaSessionManager(engine, playlistManager)
        }

        when (action) {
            ACTION_PLAY -> mediaSessionManager?.play()
            ACTION_PAUSE -> mediaSessionManager?.pause()
            ACTION_PLAY_PAUSE -> mediaSessionManager?.togglePlayPause()
            ACTION_PREV -> mediaSessionManager?.skipToPrevious()
            ACTION_NEXT -> mediaSessionManager?.skipToNext()
            ACTION_STOP -> {
                engine.pause()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (keyEvent != null) {
                    mediaSessionManager?.handleKeyEvent(keyEvent)
                }
            }
        }

        updateNotification()
        return START_STICKY
    }

    fun buildNotification(): Notification {
        val isPlaying = engine.isPlaying.value
        val title = engine.mediaTitle.value.ifBlank { null }
            ?: getString(R.string.player_default_title)
        val contentText = if (isPlaying) {
            getString(R.string.notification_playing)
        } else {
            getString(R.string.notification_paused)
        }

        val duration = engine.durationMs.value
        val position = engine.timePosMs.value

        val mediaStyle = Notification.MediaStyle().apply {
            mediaSessionManager?.sessionToken?.let { token ->
                setMediaSession(token)
            }
            setShowActionsInCompactView(0, 1, 2)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setStyle(mediaStyle)

        if (duration > 0L) {
            builder.setProgress((duration / 1000).toInt(), (position / 1000).toInt(), false)
        }

        // Action 0: Previous
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                getString(R.string.action_prev),
                createActionPendingIntent(ACTION_PREV, 1)
            ).build()
        )

        // Action 1: Play / Pause toggle
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) getString(R.string.action_pause) else getString(R.string.action_play)
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, playPauseIcon),
                playPauseTitle,
                createActionPendingIntent(playPauseAction, 2)
            ).build()
        )

        // Action 2: Next
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                getString(R.string.action_next),
                createActionPendingIntent(ACTION_NEXT, 3)
            ).build()
        )

        // Action 3: Stop
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.action_stop),
                createActionPendingIntent(ACTION_STOP, 4)
            ).build()
        )

        return builder.build()
    }

    private fun updateNotification() {
        val notification = try {
            buildNotification()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to build notification: ${t.message}")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to startForeground: ${t.message}")
        }
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "PlaybackService destroyed")
        isRunning = false
        mediaSessionManager?.release()
        mediaSessionManager = null
    }
}
