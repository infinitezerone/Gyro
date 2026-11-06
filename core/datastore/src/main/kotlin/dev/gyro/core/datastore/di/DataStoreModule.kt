package dev.gyro.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.datastore.CustomFolderDataStoreFactory
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Distinguishes the folder-grant store from [UserSettings]: a raw `DataStore<List<String>>` at an
 * injection site says nothing about which file it reads.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CustomFolderUris

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideUserSettingsDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<UserSettings> = UserSettingsDataStoreFactory.create(context, scope)

    @Provides
    @Singleton
    @CustomFolderUris
    fun provideCustomFolderUrisDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<List<String>> = CustomFolderDataStoreFactory.create(context, scope)
}
