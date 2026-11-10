package dev.gyro.player.di

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.engine.session.MediaSessionManager
import dev.gyro.feature.player.session.PlaybackSessionOrchestrator
import dev.gyro.player.service.PlaybackService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideGyroPlayerEngine(@ApplicationContext context: Context): GyroPlayerEngine {
        return GyroPlayerEngine(context)
    }

    @Provides
    @Singleton
    fun providePlaylistManager(engine: GyroPlayerEngine): PlaylistManager {
        return engine.playlist
    }

    @Provides
    @Singleton
    fun provideMediaSessionManager(
        @ApplicationContext context: Context,
        engine: GyroPlayerEngine,
        playlistManager: PlaylistManager,
        orchestrator: PlaybackSessionOrchestrator
    ): MediaSessionManager {
        val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, PlaybackService::class.java)
        val buttonPendingIntent = PendingIntent.getService(
            context,
            0,
            buttonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return MediaSessionManager(
            context = context,
            engine = engine,
            playlistManager = playlistManager,
            mediaButtonReceiver = buttonPendingIntent
        ).apply {
            onSkipToNext = {
                orchestrator.playNext()
            }
            onSkipToPrevious = {
                orchestrator.playPrevious()
            }
            onStopRequested = {
                PlaybackService.stop(context)
            }
        }
    }
}
