package dev.gyro.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
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
    fun providePlaylistManager(): PlaylistManager {
        return PlaylistManager()
    }
}
