package dev.gyro.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.RealMpvNativeBridge
import dev.gyro.engine.shader.ShaderManager
import javax.inject.Singleton

/**
 * AppModule provides engine-level and app-level components.
 *
 * Repository layer dependencies are provided by :core:data, :core:database, and :core:datastore modules.
 * :core:engine deliberately has no Hilt dependency; GyroPlayerEngine is provided from here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

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
