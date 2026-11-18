package dev.gyro.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
}
