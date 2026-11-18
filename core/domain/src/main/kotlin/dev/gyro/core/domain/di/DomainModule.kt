package dev.gyro.core.domain.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.domain.session.DefaultPlaybackSession
import dev.gyro.core.domain.session.PlaybackSession
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackSession(impl: DefaultPlaybackSession): PlaybackSession
}
