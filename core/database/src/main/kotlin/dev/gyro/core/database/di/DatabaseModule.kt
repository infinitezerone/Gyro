package dev.gyro.core.database.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.database.GyroDatabase
import dev.gyro.core.database.dao.PlaybackHistoryDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideGyroDatabase(
        @ApplicationContext context: Context,
    ): GyroDatabase = GyroDatabase.create(context)

    @Provides
    fun providePlaybackHistoryDao(
        database: GyroDatabase,
    ): PlaybackHistoryDao = database.playbackHistoryDao()
}
