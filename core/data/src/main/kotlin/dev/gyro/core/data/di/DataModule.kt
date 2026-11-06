package dev.gyro.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.DefaultMediaRepository
import dev.gyro.core.data.repository.DefaultMpvConfigRepository
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.DefaultSubtitleRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    fun bindSettingsRepository(
        impl: DefaultSettingsRepository,
    ): SettingsRepository

    @Binds
    fun bindPlaybackHistoryRepository(
        impl: DefaultPlaybackHistoryRepository,
    ): PlaybackHistoryRepository

    @Binds
    fun bindMediaRepository(
        impl: DefaultMediaRepository,
    ): MediaRepository

    @Binds
    fun bindMpvConfigRepository(
        impl: DefaultMpvConfigRepository,
    ): MpvConfigRepository

    @Binds
    fun bindSubtitleRepository(
        impl: DefaultSubtitleRepository,
    ): SubtitleRepository
}
