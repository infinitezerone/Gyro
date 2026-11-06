package dev.gyro.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import java.io.File

object CustomFolderDataStoreFactory {
    private const val DATASTORE_FILE = "custom_folders.json"

    fun create(
        context: Context,
        scope: CoroutineScope,
    ): DataStore<List<String>> =
        DataStoreFactory.create(
            serializer = CustomFolderUrisSerializer,
            // A corrupt file must not block startup: losing the list only means the user has to
            // grant the folder again, while throwing here would crash every launch.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
            scope = scope,
            produceFile = { context.dataStoreFile(DATASTORE_FILE) }
        )

    fun createForFile(
        file: File,
        scope: CoroutineScope,
    ): DataStore<List<String>> =
        DataStoreFactory.create(
            serializer = CustomFolderUrisSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
            scope = scope,
            produceFile = { file }
        )
}
