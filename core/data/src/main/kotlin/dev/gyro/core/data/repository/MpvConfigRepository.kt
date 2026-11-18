package dev.gyro.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Interface contract for managing the custom `mpv.conf` configuration file.
 */
interface MpvConfigRepository {
    /** Emits parsed `mpv.conf` options initially and whenever the configuration changes. */
    val parsedOptions: Flow<Map<String, String>>
        get() = flow { emit(getParsedOptions()) }

    /** Reads the conf file, creating the default template if it does not exist yet. */
    suspend fun loadConfig(): String
    suspend fun saveConfig(content: String)
    suspend fun resetToDefault(): String
    suspend fun getParsedOptions(): Map<String, String>

    companion object {
        val Empty: MpvConfigRepository = object : MpvConfigRepository {
            override val parsedOptions: Flow<Map<String, String>> = flowOf(emptyMap())
            override suspend fun loadConfig(): String = ""
            override suspend fun saveConfig(content: String) {}
            override suspend fun resetToDefault(): String = ""
            override suspend fun getParsedOptions(): Map<String, String> = emptyMap()
        }
    }
}
