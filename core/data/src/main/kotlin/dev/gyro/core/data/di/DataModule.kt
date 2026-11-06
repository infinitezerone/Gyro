package dev.gyro.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.data.repository.DefaultSubtitleRepository
import dev.gyro.core.data.repository.SubtitleRepository

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    fun bindSubtitleRepository(
        impl: DefaultSubtitleRepository,
    ): SubtitleRepository
}
