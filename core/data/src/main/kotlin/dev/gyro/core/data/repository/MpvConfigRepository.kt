package dev.gyro.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.model.MpvConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Interface contract for managing the custom `mpv.conf` configuration file.
 */
interface MpvConfigRepository {
    /** Reads the conf file, creating the default template if it does not exist yet. */
    suspend fun loadConfig(): String
    suspend fun saveConfig(content: String)
    suspend fun resetToDefault(): String
    suspend fun getParsedOptions(): Map<String, String>
}

/**
 * Default implementation of [MpvConfigRepository].
 *
 * Persists and loads the `mpv.conf` file directly under `context.filesDir`.
 */
class DefaultMpvConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MpvConfigRepository {

    private val configFile: File get() = context.filesDir.resolve("mpv.conf")

    /**
     * `writeText` is not atomic: two concurrent writes interleave bytes and leave a file that
     * parses as garbage. The mutex is a lock, not state — it does not make this class stateful.
     */
    private val writeMutex = Mutex()

    override suspend fun loadConfig(): String = withContext(ioDispatcher) {
        readFile() ?: writeMutex.withLock {
            // Another writer may have created it while we waited for the lock.
            readFile() ?: run {
                writeDefaultTemplate()
                MpvConfig.DEFAULT_TEMPLATE
            }
        }
    }

    override suspend fun saveConfig(content: String) = withContext(ioDispatcher) {
        writeMutex.withLock {
            configFile.parentFile?.mkdirs()
            configFile.writeText(content, Charsets.UTF_8)
        }
    }

    override suspend fun resetToDefault(): String = withContext(ioDispatcher) {
        writeMutex.withLock { writeDefaultTemplate() }
        MpvConfig.DEFAULT_TEMPLATE
    }

    /**
     * Pure read: startup asks for the parsed options before any file exists, and that must not
     * create one. The in-memory template stands in for the file until the editor saves it.
     */
    override suspend fun getParsedOptions(): Map<String, String> = withContext(ioDispatcher) {
        MpvConfig.parse(readFile() ?: MpvConfig.DEFAULT_TEMPLATE)
    }

    private fun readFile(): String? =
        if (configFile.isFile) {
            runCatching { configFile.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }

    private fun writeDefaultTemplate() {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(MpvConfig.DEFAULT_TEMPLATE, Charsets.UTF_8)
        }
    }
}
