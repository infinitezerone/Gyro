package dev.gyro.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.player.service.PlaybackServiceLauncherImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
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
        mpvConfigRepository: MpvConfigRepository,
        @ApplicationScope appScope: CoroutineScope,
        @Dispatcher(GyroDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @Dispatcher(GyroDispatchers.Default) defaultDispatcher: CoroutineDispatcher,
        @Dispatcher(GyroDispatchers.Main) mainDispatcher: CoroutineDispatcher,
    ): GyroPlayerEngine {
        val engine = GyroPlayerEngine(
            context = context,
            ioDispatcher = ioDispatcher,
            defaultDispatcher = defaultDispatcher,
            mainDispatcher = mainDispatcher,
        )
        appScope.launch {
            var configured = false
            combine(
                settingsRepository.settings,
                mpvConfigRepository.parsedOptions,
            ) { settings, options ->
                settings to options
            }.collect { (settings, options) ->
                if (!configured) {
                    configured = true
                    engine.configure(userOptions = options, settings = settings)
                } else {
                    engine.updateSettings(settings = settings, userOptions = options)
                }
            }
        }
        return engine
    }

    @Provides
    @Singleton
    fun providePlayerEngine(engine: GyroPlayerEngine): PlayerEngine {
        return engine
    }

    @Provides
    @Singleton
    fun providePlaybackServiceLauncher(impl: PlaybackServiceLauncherImpl): PlaybackServiceLauncher {
        return impl
    }
}
