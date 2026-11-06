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
 */
interface SettingsRepository {
    /**
     * Settings as a cold flow over the only source of truth (DataStore). Deliberately not a
     * StateFlow: a repository must not own a subscription, which is what makes it stateless and
     * safe to instantiate per call site.
     */
    val settings: Flow<UserSettings>

    suspend fun updateSettings(transform: (UserSettings) -> UserSettings): UserSettings
    suspend fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig)

    suspend fun setAutoResume(enabled: Boolean): UserSettings
    suspend fun setBackgroundPlayMode(mode: String): UserSettings
    suspend fun setSeekStepSeconds(seconds: Int): UserSettings
    suspend fun setDefaultHwdec(hwdec: String): UserSettings
    suspend fun setSubAutoLoad(enabled: Boolean): UserSettings
    suspend fun setGesturesEnabled(enabled: Boolean): UserSettings

    suspend fun setGestureConfig(config: PlayerGestureConfig)
    suspend fun setGestureLocked(locked: Boolean)
    suspend fun setGestureVolumeEnabled(enabled: Boolean)
    suspend fun setVolumeGestureEnabled(enabled: Boolean)
    suspend fun setGestureBrightnessEnabled(enabled: Boolean)
    suspend fun setBrightnessGestureEnabled(enabled: Boolean)
    suspend fun setGestureSeekEnabled(enabled: Boolean)
    suspend fun setSeekGestureEnabled(enabled: Boolean)
    suspend fun setGestureZoomEnabled(enabled: Boolean)
    suspend fun setZoomGestureEnabled(enabled: Boolean)
    suspend fun setGestureDoubleTapSeekEnabled(enabled: Boolean)
    suspend fun setDoubleTapSeekEnabled(enabled: Boolean)
    suspend fun setGestureDoubleTapPlayPauseEnabled(enabled: Boolean)
    suspend fun setDoubleTapPlayPauseEnabled(enabled: Boolean)
    suspend fun setGestureLongPressSpeedEnabled(enabled: Boolean)
    suspend fun setLongPressSpeedEnabled(enabled: Boolean)
    suspend fun setGestureSwapVolumeBrightness(swapped: Boolean)
    suspend fun setSwapVolumeAndBrightness(swap: Boolean)
    suspend fun setGestureDoubleTapSeekSeconds(seconds: Int): UserSettings
    suspend fun setDoubleTapSeekSeconds(seconds: Int): UserSettings
    suspend fun setGestureLongPressSpeed(speed: Float)
    suspend fun setLongPressSpeed(speed: Float)
    suspend fun setGestureVolumeSensitivity(sensitivity: Float)
    suspend fun setVolumeSensitivity(sensitivity: Float)
    suspend fun setGestureBrightnessSensitivity(sensitivity: Float)
    suspend fun setBrightnessSensitivity(sensitivity: Float)
    suspend fun setGestureEdgeExclusionPx(px: Float)
    suspend fun setLongPressTimeoutMs(timeoutMs: Long)
    suspend fun setDoubleTapTimeoutMs(timeoutMs: Long)
    suspend fun setSingleTapDelayMs(delayMs: Long)

    suspend fun resetGestureConfigToDefault()
    suspend fun resetGestureConfigToDefaults()
}

/**
 * DataStore is the single source of truth, and this class holds nothing of its own.
 *
 * [settings] is DataStore's `data` flow with an IOException fallback, so every consumer sees the
 * same values regardless of how many instances exist. Writes go through [DataStore.updateData],
 * which serializes them and computes each one against the latest persisted value.
 *
 * No StateFlow, no mirror, no scope: shared state belongs to whoever collects (a ViewModel with
 * `stateIn`, a `collectAsState` in Compose), not to the repository.
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

    override suspend fun setSubAutoLoad(enabled: Boolean) =
        updateSettings { it.copy(subAutoLoad = enabled) }

    override suspend fun setGesturesEnabled(enabled: Boolean) =
        updateSettings { it.copy(gesturesEnabled = enabled) }

    override suspend fun setGestureConfig(config: PlayerGestureConfig) = updateGestureConfig { config }
    override suspend fun setGestureLocked(locked: Boolean) = updateGestureConfig { it.copy(isLocked = locked) }
    override suspend fun setGestureVolumeEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isVolumeEnabled = enabled) }
    override suspend fun setVolumeGestureEnabled(enabled: Boolean) = setGestureVolumeEnabled(enabled)
    override suspend fun setGestureBrightnessEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isBrightnessEnabled = enabled) }
    override suspend fun setBrightnessGestureEnabled(enabled: Boolean) = setGestureBrightnessEnabled(enabled)
    override suspend fun setGestureSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isSeekEnabled = enabled) }
    override suspend fun setSeekGestureEnabled(enabled: Boolean) = setGestureSeekEnabled(enabled)
    override suspend fun setGestureZoomEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isZoomEnabled = enabled) }
    override suspend fun setZoomGestureEnabled(enabled: Boolean) = setGestureZoomEnabled(enabled)
    override suspend fun setGestureDoubleTapSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapSeekEnabled = enabled) }
    override suspend fun setDoubleTapSeekEnabled(enabled: Boolean) = setGestureDoubleTapSeekEnabled(enabled)
    override suspend fun setGestureDoubleTapPlayPauseEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapPlayPauseEnabled = enabled) }
    override suspend fun setDoubleTapPlayPauseEnabled(enabled: Boolean) = setGestureDoubleTapPlayPauseEnabled(enabled)
    override suspend fun setGestureLongPressSpeedEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isLongPressSpeedEnabled = enabled) }
    override suspend fun setLongPressSpeedEnabled(enabled: Boolean) = setGestureLongPressSpeedEnabled(enabled)
    override suspend fun setGestureSwapVolumeBrightness(swapped: Boolean) = updateGestureConfig { it.copy(swapVolumeAndBrightness = swapped) }
    override suspend fun setSwapVolumeAndBrightness(swap: Boolean) = setGestureSwapVolumeBrightness(swap)
    override suspend fun setGestureDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    override suspend fun setDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    override suspend fun setGestureLongPressSpeed(speed: Float) = updateGestureConfig { it.copy(longPressSpeed = speed) }
    override suspend fun setLongPressSpeed(speed: Float) = setGestureLongPressSpeed(speed)
    override suspend fun setGestureVolumeSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(volumeSensitivity = sensitivity) }
    override suspend fun setVolumeSensitivity(sensitivity: Float) = setGestureVolumeSensitivity(sensitivity)
    override suspend fun setGestureBrightnessSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(brightnessSensitivity = sensitivity) }
    override suspend fun setBrightnessSensitivity(sensitivity: Float) = setGestureBrightnessSensitivity(sensitivity)
    override suspend fun setGestureEdgeExclusionPx(px: Float) = updateGestureConfig { it.copy(edgeExclusionPx = px) }
    override suspend fun setLongPressTimeoutMs(timeoutMs: Long) = updateGestureConfig { it.copy(longPressTimeoutMs = timeoutMs) }
    override suspend fun setDoubleTapTimeoutMs(timeoutMs: Long) = updateGestureConfig { it.copy(doubleTapTimeoutMs = timeoutMs) }
    override suspend fun setSingleTapDelayMs(delayMs: Long) = updateGestureConfig { it.copy(singleTapDelayMs = delayMs) }

    override suspend fun resetGestureConfigToDefault() {
        updateGestureConfig { PlayerGestureConfig() }
    }

    override suspend fun resetGestureConfigToDefaults() {
        resetGestureConfigToDefault()
    }
}

/**
 * Test-only construction path: callers supply their own DataStore (usually an in-memory or
 * file-backed one) so the repository can be exercised without Android.
 */
fun SettingsRepository(dataStore: DataStore<UserSettings>): SettingsRepository =
    DefaultSettingsRepository(dataStore)
