package dev.gyro.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.engine.GyroPlayerEngine
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
    fun providePlaybackServiceLauncher(impl: PlaybackServiceLauncherImpl): PlaybackServiceLauncher {
        return impl
    }
}
