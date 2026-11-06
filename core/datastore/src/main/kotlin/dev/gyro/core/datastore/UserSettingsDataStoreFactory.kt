package dev.gyro.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import java.io.File

object UserSettingsDataStoreFactory {
    private const val DATASTORE_FILE = "user_settings.json"

    fun create(
        context: Context,
        scope: CoroutineScope,
    ): DataStore<UserSettings> =
        DataStoreFactory.create(
            serializer = UserSettingsSerializer,
            scope = scope,
            produceFile = { context.dataStoreFile(DATASTORE_FILE) }
        )

    fun createForFile(
        file: File,
        scope: CoroutineScope,
    ): DataStore<UserSettings> =
        DataStoreFactory.create(
            serializer = UserSettingsSerializer,
            scope = scope,
            produceFile = { file }
        )

    fun createInMemory(
        initialSettings: UserSettings = UserSettings(),
        scope: CoroutineScope,
    ): DataStore<UserSettings> {
        val tempFile = File.createTempFile("settings_test", ".json").apply {
            deleteOnExit()
            outputStream().use { 
                kotlinx.coroutines.runBlocking {
                    UserSettingsSerializer.writeTo(initialSettings, it)
                }
            }
        }
        return createForFile(tempFile, scope)
    }
}
