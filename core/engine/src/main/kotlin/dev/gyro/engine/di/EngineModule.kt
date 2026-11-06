package dev.gyro.engine.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.RealMpvNativeBridge
import dev.gyro.engine.effects.AudioEffectsController
import dev.gyro.engine.effects.RealAudioEffectsController
import dev.gyro.engine.effects.RealVideoEffectsController
import dev.gyro.engine.effects.VideoEffectsController
import dev.gyro.engine.session.RealVideoGeometryCoordinator
import dev.gyro.engine.session.VideoGeometryCoordinator
import dev.gyro.engine.tracks.RealTrackAndChapterManager
import dev.gyro.engine.tracks.TrackAndChapterManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface EngineModule {

    @Binds
    @Singleton
    fun bindMpvNativeBridge(
        impl: RealMpvNativeBridge
    ): MpvNativeBridge

    @Binds
    @Singleton
    fun bindAudioEffectsController(
        impl: RealAudioEffectsController
    ): AudioEffectsController

    @Binds
    @Singleton
    fun bindVideoEffectsController(
        impl: RealVideoEffectsController
    ): VideoEffectsController

    @Binds
    @Singleton
    fun bindTrackAndChapterManager(
        impl: RealTrackAndChapterManager
    ): TrackAndChapterManager

    @Binds
    @Singleton
    fun bindVideoGeometryCoordinator(
        impl: RealVideoGeometryCoordinator
    ): VideoGeometryCoordinator
}
