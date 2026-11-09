package dev.gyro.core.data.repository

import androidx.datastore.core.DataStore
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import javax.inject.Inject

/**
 * Interface contract for managing application settings and gesture configurations.
 *
 * All operations exposed by this repository are main-safe. Data is emitted as a cold [Flow]
 * to adhere to the Single Source of Truth (SSOT) architecture.
 */
interface SettingsRepository {
    /**
     * Cold flow observing persisted settings.
     */
    val settings: Flow<UserSettings>

    suspend fun updateSettings(transform: (UserSettings) -> UserSettings): UserSettings
    suspend fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig)

    suspend fun setAutoResume(enabled: Boolean): UserSettings
    suspend fun setAutoPip(enabled: Boolean): UserSettings
    suspend fun setBackgroundAudio(enabled: Boolean): UserSettings
    suspend fun setBackgroundPlayMode(mode: String): UserSettings
    suspend fun setSeekStepSeconds(seconds: Int): UserSettings
    suspend fun setDefaultHwdec(hwdec: String): UserSettings
    suspend fun setHdrOutputMode(mode: String): UserSettings
    suspend fun setSubAutoLoad(enabled: Boolean): UserSettings
    suspend fun setGesturesEnabled(enabled: Boolean): UserSettings

    suspend fun setGestureConfig(config: PlayerGestureConfig)
    suspend fun resetGestureConfigToDefault()
}

/**
 * Default implementation of [SettingsRepository] backed by Jetpack DataStore.
 *
 * DataStore is the single source of truth, and this class holds nothing of its own.
 * Writes go through [DataStore.updateData], which serializes them against the latest persisted value.
 */
class DefaultSettingsRepository @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
) : SettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(UserSettings()) else throw cause
        }

    override suspend fun updateSettings(transform: (UserSettings) -> UserSettings): UserSettings =
        dataStore.updateData { current -> transform(current) }

    override suspend fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig) {
        updateSettings { current ->
            val updatedConfig = transform(current.gestureConfig)
            current.copy(
                seekStepSeconds = updatedConfig.doubleTapSeekSeconds,
                gestureConfig = updatedConfig
            )
        }
    }

    override suspend fun setAutoResume(enabled: Boolean) =
        updateSettings { it.copy(autoResume = enabled) }

    override suspend fun setAutoPip(enabled: Boolean) =
        updateSettings { it.copy(autoPip = enabled) }

    override suspend fun setBackgroundAudio(enabled: Boolean) =
        updateSettings { it.copy(backgroundAudio = enabled) }

    override suspend fun setBackgroundPlayMode(mode: String) =
        updateSettings { it.copy(backgroundPlayMode = mode) }

    override suspend fun setSeekStepSeconds(seconds: Int) = updateSettings { current ->
        current.copy(
            seekStepSeconds = seconds,
            gestureConfig = current.gestureConfig.copy(doubleTapSeekSeconds = seconds)
        )
    }

    override suspend fun setDefaultHwdec(hwdec: String) =
        updateSettings { it.copy(defaultHwdec = hwdec) }

    override suspend fun setHdrOutputMode(mode: String) =
        updateSettings { it.copy(hdrOutputMode = mode) }

    override suspend fun setSubAutoLoad(enabled: Boolean) =
        updateSettings { it.copy(subAutoLoad = enabled) }

    override suspend fun setGesturesEnabled(enabled: Boolean) =
        updateSettings { it.copy(gesturesEnabled = enabled) }

    override suspend fun setGestureConfig(config: PlayerGestureConfig) = updateGestureConfig { config }

    override suspend fun resetGestureConfigToDefault() {
        updateGestureConfig { PlayerGestureConfig() }
    }
}
