package dev.gyro.core.data.repository

import androidx.datastore.core.DataStore
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [SettingsRepository] backed by Jetpack DataStore.
 */
@Singleton
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
                gestureConfig = updatedConfig,
            )
        }
    }

    override suspend fun setAutoResume(enabled: Boolean) =
        updateSettings { it.copy(autoResume = enabled) }

    override suspend fun setAutoPip(enabled: Boolean) =
        updateSettings { it.copy(autoPip = enabled) }

    override suspend fun setBackgroundAudio(enabled: Boolean) =
        updateSettings { it.copy(backgroundAudio = enabled) }

    override suspend fun setSeekStepSeconds(seconds: Int) = updateSettings { current ->
        current.copy(
            seekStepSeconds = seconds,
            gestureConfig = current.gestureConfig.copy(doubleTapSeekSeconds = seconds),
        )
    }

    override suspend fun setDefaultHwdec(hwdec: String) =
        updateSettings { it.copy(defaultHwdec = hwdec) }

    override suspend fun setHdrOutputMode(mode: String) =
        updateSettings { it.copy(hdrOutputMode = mode) }

    override suspend fun setToneMappingAlgorithm(algorithm: String) =
        updateSettings { it.copy(toneMappingAlgorithm = algorithm) }

    override suspend fun setVideoScale(scale: String) =
        updateSettings { it.copy(videoScale = scale) }

    override suspend fun setInterpolation(enabled: Boolean) =
        updateSettings { it.copy(interpolation = enabled) }

    override suspend fun setAudioPassthrough(enabled: Boolean) =
        updateSettings { it.copy(audioPassthrough = enabled) }

    override suspend fun setSubAutoLoad(enabled: Boolean) =
        updateSettings { it.copy(subAutoLoad = enabled) }

    override suspend fun setGesturesEnabled(enabled: Boolean) =
        updateSettings { it.copy(gesturesEnabled = enabled) }

    override suspend fun setGestureConfig(config: PlayerGestureConfig) = updateGestureConfig { config }

    override suspend fun resetGestureConfigToDefault() {
        updateGestureConfig { PlayerGestureConfig() }
    }
}
