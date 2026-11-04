package dev.gyro.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsRepository(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val lock = Any()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadGestureConfig(): PlayerGestureConfig {
        val doubleTapSeekSec = if (prefs.contains(KEY_GESTURE_DOUBLE_TAP_SEEK_SEC)) {
            prefs.getInt(KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10)
        } else {
            prefs.getInt(KEY_SEEK_STEP_SEC, 10)
        }
        val swap = if (prefs.contains(KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS)) {
            prefs.getBoolean(KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, false)
        } else {
            prefs.getBoolean("gesture_swap_vol_bright", false)
        }
        val longPressSpeed = if (prefs.contains(KEY_GESTURE_LONG_PRESS_SPEED)) {
            prefs.getFloat(KEY_GESTURE_LONG_PRESS_SPEED, 2.0f)
        } else {
            prefs.getFloat("gesture_long_press_speed_val", 2.0f)
        }
        val volSens = if (prefs.contains(KEY_GESTURE_VOLUME_SENSITIVITY)) {
            prefs.getFloat(KEY_GESTURE_VOLUME_SENSITIVITY, 1.0f)
        } else {
            prefs.getFloat("gesture_vol_sensitivity", 1.0f)
        }

        return PlayerGestureConfig(
            isLocked = prefs.getBoolean(KEY_GESTURE_IS_LOCKED, false),
            isVolumeEnabled = prefs.getBoolean(KEY_GESTURE_VOLUME_ENABLED, true),
            isBrightnessEnabled = prefs.getBoolean(KEY_GESTURE_BRIGHTNESS_ENABLED, true),
            isSeekEnabled = prefs.getBoolean(KEY_GESTURE_SEEK_ENABLED, true),
            isZoomEnabled = prefs.getBoolean(KEY_GESTURE_ZOOM_ENABLED, true),
            isDoubleTapSeekEnabled = prefs.getBoolean(KEY_GESTURE_DOUBLE_TAP_SEEK_ENABLED, true),
            isDoubleTapPlayPauseEnabled = prefs.getBoolean(KEY_GESTURE_DOUBLE_TAP_PLAY_PAUSE_ENABLED, true),
            isLongPressSpeedEnabled = prefs.getBoolean(KEY_GESTURE_LONG_PRESS_SPEED_ENABLED, true),
            swapVolumeAndBrightness = swap,
            doubleTapSeekSeconds = doubleTapSeekSec,
            longPressSpeed = longPressSpeed,
            edgeExclusionPx = prefs.getFloat(KEY_GESTURE_EDGE_EXCLUSION_PX, 72f),
            volumeSensitivity = volSens,
            brightnessSensitivity = prefs.getFloat(KEY_GESTURE_BRIGHTNESS_SENSITIVITY, 1.0f),
            longPressTimeoutMs = prefs.getLong(KEY_GESTURE_LONG_PRESS_TIMEOUT_MS, 450L),
            doubleTapTimeoutMs = prefs.getLong(KEY_GESTURE_DOUBLE_TAP_TIMEOUT_MS, 320L),
            singleTapDelayMs = prefs.getLong(KEY_GESTURE_SINGLE_TAP_DELAY_MS, 260L)
        )
    }

    private fun loadSettings(): UserSettings {
        val gestureConfig = loadGestureConfig()
        val seekStep = if (prefs.contains(KEY_SEEK_STEP_SEC)) {
            prefs.getInt(KEY_SEEK_STEP_SEC, 10)
        } else {
            gestureConfig.doubleTapSeekSeconds
        }
        return UserSettings(
            autoResume = prefs.getBoolean(KEY_AUTO_RESUME, true),
            backgroundPlayMode = prefs.getString(KEY_BG_PLAY_MODE, "pip") ?: "pip",
            seekStepSeconds = seekStep,
            defaultHwdec = prefs.getString(KEY_DEFAULT_HWDEC, "no") ?: "no",
            subAutoLoad = prefs.getBoolean(KEY_SUB_AUTO_LOAD, true),
            gesturesEnabled = prefs.getBoolean(KEY_GESTURES_ENABLED, true),
            gestureConfig = gestureConfig
        )
    }

    private fun saveGestureConfig(config: PlayerGestureConfig) {
        prefs.edit()
            .putBoolean(KEY_GESTURE_IS_LOCKED, config.isLocked)
            .putBoolean(KEY_GESTURE_VOLUME_ENABLED, config.isVolumeEnabled)
            .putBoolean(KEY_GESTURE_BRIGHTNESS_ENABLED, config.isBrightnessEnabled)
            .putBoolean(KEY_GESTURE_SEEK_ENABLED, config.isSeekEnabled)
            .putBoolean(KEY_GESTURE_ZOOM_ENABLED, config.isZoomEnabled)
            .putBoolean(KEY_GESTURE_DOUBLE_TAP_SEEK_ENABLED, config.isDoubleTapSeekEnabled)
            .putBoolean(KEY_GESTURE_DOUBLE_TAP_PLAY_PAUSE_ENABLED, config.isDoubleTapPlayPauseEnabled)
            .putBoolean(KEY_GESTURE_LONG_PRESS_SPEED_ENABLED, config.isLongPressSpeedEnabled)
            .putBoolean(KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, config.swapVolumeAndBrightness)
            .putInt(KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, config.doubleTapSeekSeconds)
            .putInt(KEY_SEEK_STEP_SEC, config.doubleTapSeekSeconds)
            .putFloat(KEY_GESTURE_LONG_PRESS_SPEED, config.longPressSpeed)
            .putFloat(KEY_GESTURE_EDGE_EXCLUSION_PX, config.edgeExclusionPx)
            .putFloat(KEY_GESTURE_VOLUME_SENSITIVITY, config.volumeSensitivity)
            .putFloat(KEY_GESTURE_BRIGHTNESS_SENSITIVITY, config.brightnessSensitivity)
            .putLong(KEY_GESTURE_LONG_PRESS_TIMEOUT_MS, config.longPressTimeoutMs)
            .putLong(KEY_GESTURE_DOUBLE_TAP_TIMEOUT_MS, config.doubleTapTimeoutMs)
            .putLong(KEY_GESTURE_SINGLE_TAP_DELAY_MS, config.singleTapDelayMs)
            .apply()
    }

    // --- Base UserSettings Mutators ---

    fun setAutoResume(enabled: Boolean) = synchronized(lock) {
        prefs.edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
        _settings.update { it.copy(autoResume = enabled) }
    }

    fun setBackgroundPlayMode(mode: String) = synchronized(lock) {
        prefs.edit().putString(KEY_BG_PLAY_MODE, mode).apply()
        _settings.update { it.copy(backgroundPlayMode = mode) }
    }

    fun setSeekStepSeconds(seconds: Int) = synchronized(lock) {
        prefs.edit()
            .putInt(KEY_SEEK_STEP_SEC, seconds)
            .putInt(KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, seconds)
            .apply()
        _settings.update { current ->
            current.copy(
                seekStepSeconds = seconds,
                gestureConfig = current.gestureConfig.copy(doubleTapSeekSeconds = seconds)
            )
        }
    }

    fun setDefaultHwdec(hwdec: String) = synchronized(lock) {
        prefs.edit().putString(KEY_DEFAULT_HWDEC, hwdec).apply()
        _settings.update { it.copy(defaultHwdec = hwdec) }
    }

    fun setSubAutoLoad(enabled: Boolean) = synchronized(lock) {
        prefs.edit().putBoolean(KEY_SUB_AUTO_LOAD, enabled).apply()
        _settings.update { it.copy(subAutoLoad = enabled) }
    }

    fun setGesturesEnabled(enabled: Boolean) = synchronized(lock) {
        prefs.edit().putBoolean(KEY_GESTURES_ENABLED, enabled).apply()
        _settings.update { it.copy(gesturesEnabled = enabled) }
    }

    // --- PlayerGestureConfig Mutators ---

    fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig) = synchronized(lock) {
        val currentConfig = _settings.value.gestureConfig
        val updatedConfig = transform(currentConfig)
        saveGestureConfig(updatedConfig)
        _settings.update { current ->
            current.copy(
                seekStepSeconds = updatedConfig.doubleTapSeekSeconds,
                gestureConfig = updatedConfig
            )
        }
    }

    fun setGestureConfig(config: PlayerGestureConfig) = updateGestureConfig { config }

    fun setGestureVolumeEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isVolumeEnabled = enabled) }
    fun setGestureBrightnessEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isBrightnessEnabled = enabled) }
    fun setGestureSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isSeekEnabled = enabled) }
    fun setGestureZoomEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isZoomEnabled = enabled) }
    fun setGestureDoubleTapSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapSeekEnabled = enabled) }
    fun setGestureDoubleTapPlayPauseEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapPlayPauseEnabled = enabled) }
    fun setGestureLongPressSpeedEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isLongPressSpeedEnabled = enabled) }
    fun setGestureSwapVolumeBrightness(swapped: Boolean) = updateGestureConfig { it.copy(swapVolumeAndBrightness = swapped) }
    fun setGestureDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    fun setGestureLongPressSpeed(speed: Float) = updateGestureConfig { it.copy(longPressSpeed = speed) }
    fun setGestureVolumeSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(volumeSensitivity = sensitivity) }
    fun setGestureBrightnessSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(brightnessSensitivity = sensitivity) }
    fun setGestureEdgeExclusionPx(px: Float) = updateGestureConfig { it.copy(edgeExclusionPx = px) }

    fun resetGestureConfigToDefault() = updateGestureConfig { PlayerGestureConfig() }

    companion object {
        const val PREFS_NAME = "gyro_settings"

        const val KEY_AUTO_RESUME = "auto_resume"
        const val KEY_BG_PLAY_MODE = "bg_play_mode"
        const val KEY_SEEK_STEP_SEC = "seek_step_sec"
        const val KEY_DEFAULT_HWDEC = "default_hwdec"
        const val KEY_SUB_AUTO_LOAD = "sub_auto_load"
        const val KEY_GESTURES_ENABLED = "gestures_enabled"

        const val KEY_GESTURE_IS_LOCKED = "gesture_is_locked"
        const val KEY_GESTURE_VOLUME_ENABLED = "gesture_volume_enabled"
        const val KEY_GESTURE_BRIGHTNESS_ENABLED = "gesture_brightness_enabled"
        const val KEY_GESTURE_SEEK_ENABLED = "gesture_seek_enabled"
        const val KEY_GESTURE_ZOOM_ENABLED = "gesture_zoom_enabled"
        const val KEY_GESTURE_DOUBLE_TAP_SEEK_ENABLED = "gesture_double_tap_seek_enabled"
        const val KEY_GESTURE_DOUBLE_TAP_PLAY_PAUSE_ENABLED = "gesture_double_tap_play_pause_enabled"
        const val KEY_GESTURE_LONG_PRESS_SPEED_ENABLED = "gesture_long_press_speed_enabled"
        const val KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS = "gesture_swap_volume_brightness"
        const val KEY_GESTURE_DOUBLE_TAP_SEEK_SEC = "gesture_double_tap_seek_sec"
        const val KEY_GESTURE_LONG_PRESS_SPEED = "gesture_long_press_speed"
        const val KEY_GESTURE_EDGE_EXCLUSION_PX = "gesture_edge_exclusion_px"
        const val KEY_GESTURE_VOLUME_SENSITIVITY = "gesture_volume_sensitivity"
        const val KEY_GESTURE_BRIGHTNESS_SENSITIVITY = "gesture_brightness_sensitivity"
        const val KEY_GESTURE_LONG_PRESS_TIMEOUT_MS = "gesture_long_press_timeout_ms"
        const val KEY_GESTURE_DOUBLE_TAP_TIMEOUT_MS = "gesture_double_tap_timeout_ms"
        const val KEY_GESTURE_SINGLE_TAP_DELAY_MS = "gesture_single_tap_delay_ms"
    }
}
