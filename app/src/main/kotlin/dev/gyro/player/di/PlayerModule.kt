package dev.gyro.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.player.service.PlaybackServiceLauncherImpl
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideGyroPlayerEngine(
        @ApplicationContext context: Context,
        @Dispatcher(GyroDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @Dispatcher(GyroDispatchers.Default) defaultDispatcher: CoroutineDispatcher,
        @Dispatcher(GyroDispatchers.Main) mainDispatcher: CoroutineDispatcher,
    ): GyroPlayerEngine = GyroPlayerEngine(
        context = context,
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher,
        mainDispatcher = mainDispatcher,
    )

    @Provides
    @Singleton
    fun providePlayerEngine(engine: GyroPlayerEngine): PlayerEngine = engine

    @Provides
    @Singleton
    fun providePlaybackServiceLauncher(impl: PlaybackServiceLauncherImpl): PlaybackServiceLauncher = impl
}
