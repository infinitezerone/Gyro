package dev.gyro.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.RealMpvNativeBridge
import dev.gyro.engine.shader.ShaderManager
import javax.inject.Singleton

/**
 * Single place where the object graph is declared.
 *
 * Repositories are @Singleton on purpose: each of them owns a MutableStateFlow mirroring
 * SharedPreferences, so two instances would diverge in memory and a write through one would be
 * invisible to the other. Before this module existed, six ViewModels each constructed their own.
 *
 * :core:engine deliberately has no Hilt dependency. It is provided from here instead, because
 * :app is the only module that is allowed to know the whole graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideMpvConfigRepository(@ApplicationContext context: Context): MpvConfigRepository =
        MpvConfigRepository(context)

    @Provides
    @Singleton
    fun provideMediaRepository(@ApplicationContext context: Context): MediaRepository =
        MediaRepository(context)

    @Provides
    @Singleton
    fun providePlaybackHistoryRepository(
        @ApplicationContext context: Context,
    ): PlaybackHistoryRepository = PlaybackHistoryRepository(context)

    @Provides
    @Singleton
    fun providePlaylistManager(): PlaylistManager = PlaylistManager()

    @Provides
    @Singleton
    fun provideShaderManager(@ApplicationContext context: Context): ShaderManager =
        ShaderManager(context)

    @Provides
    @Singleton
    fun providePlayerEngine(
        @ApplicationContext context: Context,
        mpvConfigRepository: MpvConfigRepository,
        settingsRepository: SettingsRepository,
        shaderManager: ShaderManager,
    ): GyroPlayerEngine = GyroPlayerEngine(
        context = context,
        bridge = RealMpvNativeBridge,
        mpvConfigRepository = mpvConfigRepository,
        settingsRepository = settingsRepository,
        shaderManager = shaderManager,
    )
}
