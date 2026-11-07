package dev.gyro.engine.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.RealMpvNativeBridge
import dev.gyro.engine.audio.AudioEffectsController
import dev.gyro.engine.audio.RealAudioEffectsController
import dev.gyro.engine.video.RealVideoEffectsController
import dev.gyro.engine.video.VideoEffectsController
import dev.gyro.engine.media.DefaultMediaResourceResolver
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.engine.session.PlaybackTimelineController
import dev.gyro.engine.session.RealPlaybackTimelineController
import dev.gyro.engine.video.RealVideoGeometryCoordinator
import dev.gyro.engine.video.VideoGeometryCoordinator
import dev.gyro.engine.video.RealShaderController
import dev.gyro.engine.video.ShaderController
import dev.gyro.engine.tracks.RealTrackAndChapterManager
import dev.gyro.engine.tracks.TrackAndChapterManager
import dev.gyro.engine.tuning.RealSyncTuningController
import dev.gyro.engine.tuning.SyncTuningController
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
    fun bindMediaResourceResolver(
        impl: DefaultMediaResourceResolver
    ): MediaResourceResolver

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

    @Binds
    @Singleton
    fun bindPlaybackTimelineController(
        impl: RealPlaybackTimelineController
    ): PlaybackTimelineController

    @Binds
    @Singleton
    fun bindSyncTuningController(
        impl: RealSyncTuningController
    ): SyncTuningController

    @Binds
    @Singleton
    fun bindShaderController(
        impl: RealShaderController
    ): ShaderController
}
