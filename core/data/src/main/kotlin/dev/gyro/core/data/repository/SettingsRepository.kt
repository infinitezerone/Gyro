package dev.gyro.core.data.repository

import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * Interface contract for managing application settings and gesture configurations.
 *
 * All operations exposed by this repository are main-safe. Data is emitted as a cold [Flow]
 * to adhere to the Single Source of Truth (SSOT) architecture.
 */
interface SettingsRepository {
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
    suspend fun setToneMappingAlgorithm(algorithm: String): UserSettings
    suspend fun setVideoScale(scale: String): UserSettings
    suspend fun setInterpolation(enabled: Boolean): UserSettings
    suspend fun setAudioPassthrough(enabled: Boolean): UserSettings
    suspend fun setSubAutoLoad(enabled: Boolean): UserSettings
    suspend fun setGesturesEnabled(enabled: Boolean): UserSettings

    suspend fun setGestureConfig(config: PlayerGestureConfig)
    suspend fun resetGestureConfigToDefault()
}
