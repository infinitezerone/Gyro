package dev.gyro.core.data.repository

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
