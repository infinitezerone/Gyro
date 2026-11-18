package dev.gyro.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.model.MpvConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [MpvConfigRepository].
 *
 * Persists and loads the `mpv.conf` file directly under `context.filesDir`.
 */
@Singleton
class DefaultMpvConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : MpvConfigRepository {

    private val configFile: File get() = context.filesDir.resolve("mpv.conf")
    private val tmpConfigFile: File get() = context.filesDir.resolve("mpv.conf.tmp")

    /**
     * `writeText` is not atomic: two concurrent writes interleave bytes and leave a file that
     * parses as garbage. The mutex is a lock, not state — it does not make this class stateful.
     */
    private val writeMutex = Mutex()
    private val configVersion = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val parsedOptions: Flow<Map<String, String>> = configVersion
        .flatMapLatest { flow { emit(getParsedOptions()) } }
        .distinctUntilChanged()

    override suspend fun loadConfig(): String = withContext(ioDispatcher) {
        readFile() ?: writeMutex.withLock {
            readFile() ?: run {
                writeDefaultTemplate()
                MpvConfig.DEFAULT_TEMPLATE
            }
        }
    }

    override suspend fun saveConfig(content: String) = withContext(ioDispatcher) {
        writeMutex.withLock {
            writeAtomically(content)
            configVersion.update { it + 1 }
        }
    }

    override suspend fun resetToDefault(): String = withContext(ioDispatcher) {
        writeMutex.withLock {
            writeDefaultTemplate()
            configVersion.update { it + 1 }
        }
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
            writeAtomically(MpvConfig.DEFAULT_TEMPLATE)
        }
    }

    private fun writeAtomically(content: String) {
        configFile.parentFile?.mkdirs()
        val tmpFile = tmpConfigFile
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            Files.move(
                tmpFile.toPath(),
                configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            runCatching { tmpFile.delete() }
            throw t
        }
    }
}
