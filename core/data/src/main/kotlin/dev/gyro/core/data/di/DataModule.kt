package dev.gyro.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.data.repository.DefaultCustomFolderRepository
import dev.gyro.core.data.repository.DefaultMediaRepository
import dev.gyro.core.data.repository.DefaultMpvConfigRepository
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultPowerRepository
import dev.gyro.core.data.repository.DefaultScreenshotRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.DefaultSubtitleRepository
import dev.gyro.core.data.repository.DefaultThumbnailRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PowerRepository
import dev.gyro.core.data.repository.ScreenshotRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.ThumbnailRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun bindThumbnailRepository(
        impl: DefaultThumbnailRepository,
    ): ThumbnailRepository

    @Binds
    @Singleton
    fun bindCustomFolderRepository(
        impl: DefaultCustomFolderRepository,
    ): CustomFolderRepository

    @Binds
    @Singleton
    fun bindSettingsRepository(
        impl: DefaultSettingsRepository,
    ): SettingsRepository

    @Binds
    @Singleton
    fun bindPlaybackHistoryRepository(
        impl: DefaultPlaybackHistoryRepository,
    ): PlaybackHistoryRepository

    @Binds
    @Singleton
    fun bindMediaRepository(
        impl: DefaultMediaRepository,
    ): MediaRepository

    @Binds
    @Singleton
    fun bindMpvConfigRepository(
        impl: DefaultMpvConfigRepository,
    ): MpvConfigRepository

    @Binds
    @Singleton
    fun bindSubtitleRepository(
        impl: DefaultSubtitleRepository,
    ): SubtitleRepository

    @Binds
    @Singleton
    fun bindScreenshotRepository(
        impl: DefaultScreenshotRepository,
    ): ScreenshotRepository

    @Binds
    @Singleton
    fun bindPowerRepository(
        impl: DefaultPowerRepository,
    ): PowerRepository
}
