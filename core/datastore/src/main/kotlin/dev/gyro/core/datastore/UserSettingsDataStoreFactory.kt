package dev.gyro.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dev.gyro.core.model.UserSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope

object UserSettingsDataStoreFactory {
    private const val DATASTORE_FILE = "user_settings.json"

    fun create(
        context: Context,
        scope: CoroutineScope,
    ): DataStore<UserSettings> =
        createForFile(context.dataStoreFile(DATASTORE_FILE), scope)

    fun createForFile(
        file: File,
        scope: CoroutineScope,
    ): DataStore<UserSettings> =
        DataStoreFactory.create(
            serializer = UserSettingsSerializer,
            // A corrupt file must not block startup: resetting to defaults loses preferences, while
            // throwing here would crash every launch (the repository only catches IOException).
            corruptionHandler = ReplaceFileCorruptionHandler { UserSettings() },
            scope = scope,
            produceFile = { file },
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
