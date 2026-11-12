package dev.gyro.player.di

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.session.MediaSessionManager
import dev.gyro.core.playback.PlaybackServiceLauncher
import dev.gyro.player.MainActivity
import dev.gyro.player.service.PlaybackService
import dev.gyro.player.service.PlaybackServiceLauncherImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideGyroPlayerEngine(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        mpvConfigRepository: MpvConfigRepository
    ): GyroPlayerEngine {
        val engine = GyroPlayerEngine(context)
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            val initialSettings = settingsRepository.settings.first()
            engine.configure(
                userOptions = mpvConfigRepository.getParsedOptions(),
                settings = initialSettings
            )
            settingsRepository.settings.collect { updatedSettings ->
                engine.updateSettings(
                    settings = updatedSettings,
                    userOptions = mpvConfigRepository.getParsedOptions()
                )
            }
        }
        return engine
    }

    @Provides
    @Singleton
    fun providePlayerEngine(engine: GyroPlayerEngine): dev.gyro.engine.api.PlayerEngine {
        return engine
    }

    @Provides
    @Singleton
    fun provideMediaSessionManager(
        @ApplicationContext context: Context,
        engine: dev.gyro.engine.api.PlayerEngine,
        sessionController: dev.gyro.core.playback.PlaybackSessionController
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
            mediaButtonReceiver = buttonPendingIntent,
            sessionActivityPendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).apply {
            onStopRequested = {
                PlaybackService.stop(context)
            }
            // The only implementation of "next/previous" lives in the session controller: it saves
            // the outgoing item's progress and resolves the incoming one's resume point. The
            // session manager and the Media3 adapter must not move the queue cursor themselves.
            onSkipToNext = { sessionController.playNext() }
            onSkipToPrevious = { sessionController.playPrevious() }
        }
    }

    @Provides
    @Singleton
    fun providePlaybackServiceLauncher(impl: PlaybackServiceLauncherImpl): PlaybackServiceLauncher {
        return impl
    }
}
