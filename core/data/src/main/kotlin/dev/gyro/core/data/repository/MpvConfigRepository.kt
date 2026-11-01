package dev.gyro.core.data.repository

import android.content.Context
import dev.gyro.core.model.MpvConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistent repository for custom mpv.conf file.
 * Backed by an internal application file (context.filesDir/mpv.conf)
 * and exposing reactive StateFlow for Compose UI observation.
 */
class MpvConfigRepository(
    private val configFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        configFile = context.filesDir.resolve("mpv.conf"),
        ioDispatcher = ioDispatcher
    )

    private val mutex = Mutex()

    private val _configText = MutableStateFlow(loadSync())
    val configText: StateFlow<String> = _configText.asStateFlow()

    private fun loadSync(): String {
        return try {
            if (configFile.exists()) {
                configFile.readText(Charsets.UTF_8)
            } else {
                configFile.parentFile?.mkdirs()
                configFile.writeText(MpvConfig.DEFAULT_TEMPLATE, Charsets.UTF_8)
                MpvConfig.DEFAULT_TEMPLATE
            }
        } catch (e: Exception) {
            MpvConfig.DEFAULT_TEMPLATE
        }
    }

    suspend fun saveConfig(content: String): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            configFile.parentFile?.mkdirs()
            configFile.writeText(content, Charsets.UTF_8)
            _configText.value = content
        }
    }

    suspend fun resetToDefault(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            configFile.parentFile?.mkdirs()
            configFile.writeText(MpvConfig.DEFAULT_TEMPLATE, Charsets.UTF_8)
            _configText.value = MpvConfig.DEFAULT_TEMPLATE
        }
    }

    fun getParsedOptions(): Map<String, String> = MpvConfig.parse(_configText.value)

    fun getConfigFile(): File = configFile
}
