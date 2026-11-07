package dev.gyro.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.dataStoreFile
import dev.gyro.core.model.UserSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            storage = ReplaceExistingFileStorage { file },
            scope = scope,
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

/**
 * [Storage] implementation using NIO [Files.move] with [StandardCopyOption.REPLACE_EXISTING]
 * instead of `java.io.File.renameTo`, so multi-write atomic replacement succeeds on both Android
 * POSIX filesystems and Windows NTFS host unit tests.
 */
private class ReplaceExistingFileStorage(
    private val produceFile: () -> File,
) : Storage<UserSettings> {
    override fun createConnection(): StorageConnection<UserSettings> {
        val file = produceFile()
        return ReplaceExistingFileStorageConnection(file)
    }
}

private class ReplaceExistingFileStorageConnection(
    private val file: File,
) : StorageConnection<UserSettings> {
    private val closed = AtomicBoolean(false)
    private val transactionMutex = Mutex()

    override val coordinator: InterProcessCoordinator =
        createSingleProcessCoordinator(file.canonicalFile.absolutePath)

    override suspend fun <R> readScope(
        block: suspend ReadScope<UserSettings>.(locked: Boolean) -> R
    ): R {
        check(!closed.get()) { "StorageConnection has already been closed." }
        val lock = transactionMutex.tryLock()
        val scope = object : ReadScope<UserSettings> {
            override suspend fun readData(): UserSettings {
                return try {
                    FileInputStream(file).use { stream ->
                        UserSettingsSerializer.readFrom(stream)
                    }
                } catch (_: FileNotFoundException) {
                    UserSettingsSerializer.defaultValue
                }
            }

            override fun close() = Unit
        }
        return try {
            block(scope, lock)
        } finally {
            scope.close()
            if (lock) {
                transactionMutex.unlock()
            }
        }
    }

    override suspend fun writeScope(block: suspend WriteScope<UserSettings>.() -> Unit) {
        check(!closed.get()) { "StorageConnection has already been closed." }
        file.parentFile?.mkdirs()
        transactionMutex.withLock {
            val scratchFile = File(file.absolutePath + ".tmp")
            val scope = object : WriteScope<UserSettings> {
                override suspend fun writeData(value: UserSettings) {
                    FileOutputStream(scratchFile).use { stream ->
                        UserSettingsSerializer.writeTo(value, stream)
                        stream.fd.sync()
                    }
                }

                override suspend fun readData(): UserSettings {
                    val source = if (scratchFile.exists()) scratchFile else file
                    return try {
                        FileInputStream(source).use { stream ->
                            UserSettingsSerializer.readFrom(stream)
                        }
                    } catch (_: FileNotFoundException) {
                        UserSettingsSerializer.defaultValue
                    }
                }

                override fun close() = Unit
            }
            try {
                block(scope)
                if (scratchFile.exists()) {
                    Files.move(
                        scratchFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                scope.close()
                if (scratchFile.exists()) {
                    scratchFile.delete()
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
    }
}
