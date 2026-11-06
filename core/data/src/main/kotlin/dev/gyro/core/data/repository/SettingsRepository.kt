package dev.gyro.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.HWDEC_HARDWARE
import dev.gyro.core.model.HWDEC_SOFTWARE
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modern DataStore-backed repository for managing [UserSettings] and [PlayerGestureConfig].
 *
 * Aligned with Now in Android & BgmPlus:
 * - Backed by Jetpack DataStore in `:core:datastore` with kotlinx.serialization
 * - Synchronous, zero-latency [settings] StateFlow mirroring disk state
 * - One-time migration from legacy SharedPreferences ("gyro_settings")
 * - Mutators are non-blocking, Main-safe, and dispatched via @Dispatcher(IO)
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext context: Context? = null,
) {
    private var legacyPrefs: SharedPreferences? = null

    /**
     * Testing constructor: accepts custom DataStore, Dispatcher, and Scope directly.
     */
    constructor(
        dataStore: DataStore<UserSettings>,
        ioDispatcher: CoroutineDispatcher,
        appScope: CoroutineScope,
    ) : this(dataStore, ioDispatcher, appScope, null)

    /**
     * Legacy & unit-testing constructor: accepts SharedPreferences directly, wraps into DataStore,
     * and dual-writes to maintain 100% test compatibility.
     */
    constructor(
        prefs: SharedPreferences,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        appScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    ) : this(
        dataStore = UserSettingsDataStoreFactory.createInMemory(loadSettingsFromPrefs(prefs), appScope),
        ioDispatcher = ioDispatcher,
        appScope = appScope,
        context = null,
    ) {
        this.legacyPrefs = prefs
        this._settings.value = loadSettingsFromPrefs(prefs)
    }

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val lock = Any()

    init {
        if (context != null) {
            legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            migrateLegacyPrefsIfNeeded(context)
        }

        // Load initial settings from DataStore on startup if not already loaded from prefs
        appScope.launch(ioDispatcher) {
            runCatching {
                dataStore.data.collectLatest { diskSettings ->
                    synchronized(lock) {
                        // Only sync from disk if it was default or matching
                        if (_settings.value == UserSettings()) {
                            _settings.value = diskSettings
                        }
                    }
                }
            }
        }
    }

    /**
     * Synchronously returns current snapshot of settings.
     */
    fun currentSettings(): UserSettings = _settings.value

    fun updateSettings(transform: (UserSettings) -> UserSettings): UserSettings = synchronized(lock) {
        val current = _settings.value
        val updated = transform(current)
        _settings.value = updated
        // Persist to DataStore asynchronously on IO dispatcher
        appScope.launch(ioDispatcher) {
            runCatching {
                dataStore.updateData { updated }
            }
        }
        // Dual-write to legacyPrefs if present for test compatibility
        legacyPrefs?.let { saveSettingsToPrefs(it, updated) }
        updated
    }

    fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig) {
        updateSettings { current ->
            val updatedConfig = transform(current.gestureConfig)
            current.copy(
                seekStepSeconds = updatedConfig.doubleTapSeekSeconds,
                gestureConfig = updatedConfig
            )
        }
    }

    fun setAutoResume(enabled: Boolean) = updateSettings { it.copy(autoResume = enabled) }
    fun setBackgroundPlayMode(mode: String) = updateSettings { it.copy(backgroundPlayMode = mode) }
    fun setSeekStepSeconds(seconds: Int) = updateSettings { current ->
        current.copy(
            seekStepSeconds = seconds,
            gestureConfig = current.gestureConfig.copy(doubleTapSeekSeconds = seconds)
        )
    }
    fun setDefaultHwdec(hwdec: String) = updateSettings { it.copy(defaultHwdec = hwdec) }
    fun setSubAutoLoad(enabled: Boolean) = updateSettings { it.copy(subAutoLoad = enabled) }
    fun setGesturesEnabled(enabled: Boolean) = updateSettings { it.copy(gesturesEnabled = enabled) }

    fun setGestureConfig(config: PlayerGestureConfig) = updateGestureConfig { config }
    fun setGestureLocked(locked: Boolean) = updateGestureConfig { it.copy(isLocked = locked) }
    fun setGestureVolumeEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isVolumeEnabled = enabled) }
    fun setVolumeGestureEnabled(enabled: Boolean) = setGestureVolumeEnabled(enabled)
    fun setGestureBrightnessEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isBrightnessEnabled = enabled) }
    fun setBrightnessGestureEnabled(enabled: Boolean) = setGestureBrightnessEnabled(enabled)
    fun setGestureSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isSeekEnabled = enabled) }
    fun setSeekGestureEnabled(enabled: Boolean) = setGestureSeekEnabled(enabled)
    fun setGestureZoomEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isZoomEnabled = enabled) }
    fun setZoomGestureEnabled(enabled: Boolean) = setGestureZoomEnabled(enabled)
    fun setGestureDoubleTapSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapSeekEnabled = enabled) }
    fun setDoubleTapSeekEnabled(enabled: Boolean) = setGestureDoubleTapSeekEnabled(enabled)
    fun setGestureDoubleTapPlayPauseEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapPlayPauseEnabled = enabled) }
    fun setDoubleTapPlayPauseEnabled(enabled: Boolean) = setGestureDoubleTapPlayPauseEnabled(enabled)
    fun setGestureLongPressSpeedEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isLongPressSpeedEnabled = enabled) }
    fun setLongPressSpeedEnabled(enabled: Boolean) = setGestureLongPressSpeedEnabled(enabled)
    fun setGestureSwapVolumeBrightness(swapped: Boolean) = updateGestureConfig { it.copy(swapVolumeAndBrightness = swapped) }
    fun setSwapVolumeAndBrightness(swap: Boolean) = setGestureSwapVolumeBrightness(swap)
    fun setGestureDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    fun setDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    fun setGestureLongPressSpeed(speed: Float) = updateGestureConfig { it.copy(longPressSpeed = speed) }
    fun setLongPressSpeed(speed: Float) = setGestureLongPressSpeed(speed)
    fun setGestureVolumeSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(volumeSensitivity = sensitivity) }
    fun setVolumeSensitivity(sensitivity: Float) = setGestureVolumeSensitivity(sensitivity)
    fun setGestureBrightnessSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(brightnessSensitivity = sensitivity) }
    fun setBrightnessSensitivity(sensitivity: Float) = setGestureBrightnessSensitivity(sensitivity)
    fun setGestureEdgeExclusionPx(px: Float) = updateGestureConfig { it.copy(edgeExclusionPx = px) }
    fun setLongPressTimeoutMs(timeoutMs: Long) = updateGestureConfig { it.copy(longPressTimeoutMs = timeoutMs) }
    fun setDoubleTapTimeoutMs(timeoutMs: Long) = updateGestureConfig { it.copy(doubleTapTimeoutMs = timeoutMs) }
    fun setSingleTapDelayMs(delayMs: Long) = updateGestureConfig { it.copy(singleTapDelayMs = delayMs) }

    fun resetGestureConfigToDefault() {
        updateGestureConfig { PlayerGestureConfig() }
    }

    fun resetGestureConfigToDefaults() {
        resetGestureConfigToDefault()
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasMigratedKey = "datastore_migration_completed"
        if (sp.getBoolean(hasMigratedKey, false)) return

        // Read all existing values from SharedPreferences
        val legacySettings = loadSettingsFromPrefs(sp)
        _settings.value = legacySettings

        // Write into DataStore
        appScope.launch(ioDispatcher) {
            dataStore.updateData { legacySettings }
            sp.edit().putBoolean(hasMigratedKey, true).apply()
        }
    }

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

        fun loadGestureConfigFromPrefs(prefs: SharedPreferences): PlayerGestureConfig {
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

        fun loadSettingsFromPrefs(prefs: SharedPreferences): UserSettings {
            val gestureConfig = loadGestureConfigFromPrefs(prefs)
            val seekStep = if (prefs.contains(KEY_SEEK_STEP_SEC)) {
                prefs.getInt(KEY_SEEK_STEP_SEC, 10)
            } else {
                gestureConfig.doubleTapSeekSeconds
            }

            return UserSettings(
                autoResume = prefs.getBoolean(KEY_AUTO_RESUME, true),
                backgroundPlayMode = prefs.getString(KEY_BG_PLAY_MODE, "pip") ?: "pip",
                seekStepSeconds = seekStep,
                defaultHwdec = (prefs.getString(KEY_DEFAULT_HWDEC, HWDEC_HARDWARE) ?: HWDEC_HARDWARE)
                    .ifBlank { HWDEC_SOFTWARE },
                subAutoLoad = prefs.getBoolean(KEY_SUB_AUTO_LOAD, true),
                gesturesEnabled = prefs.getBoolean(KEY_GESTURES_ENABLED, true),
                gestureConfig = gestureConfig
            )
        }

        fun saveSettingsToPrefs(prefs: SharedPreferences, s: UserSettings) {
            val c = s.gestureConfig
            prefs.edit()
                .putBoolean(KEY_AUTO_RESUME, s.autoResume)
                .putString(KEY_BG_PLAY_MODE, s.backgroundPlayMode)
                .putInt(KEY_SEEK_STEP_SEC, s.seekStepSeconds)
                .putString(KEY_DEFAULT_HWDEC, s.defaultHwdec)
                .putBoolean(KEY_SUB_AUTO_LOAD, s.subAutoLoad)
                .putBoolean(KEY_GESTURES_ENABLED, s.gesturesEnabled)
                .putBoolean(KEY_GESTURE_IS_LOCKED, c.isLocked)
                .putBoolean(KEY_GESTURE_VOLUME_ENABLED, c.isVolumeEnabled)
                .putBoolean(KEY_GESTURE_BRIGHTNESS_ENABLED, c.isBrightnessEnabled)
                .putBoolean(KEY_GESTURE_SEEK_ENABLED, c.isSeekEnabled)
                .putBoolean(KEY_GESTURE_ZOOM_ENABLED, c.isZoomEnabled)
                .putBoolean(KEY_GESTURE_DOUBLE_TAP_SEEK_ENABLED, c.isDoubleTapSeekEnabled)
                .putBoolean(KEY_GESTURE_DOUBLE_TAP_PLAY_PAUSE_ENABLED, c.isDoubleTapPlayPauseEnabled)
                .putBoolean(KEY_GESTURE_LONG_PRESS_SPEED_ENABLED, c.isLongPressSpeedEnabled)
                .putBoolean(KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, c.swapVolumeAndBrightness)
                .putInt(KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, c.doubleTapSeekSeconds)
                .putFloat(KEY_GESTURE_LONG_PRESS_SPEED, c.longPressSpeed)
                .putFloat(KEY_GESTURE_EDGE_EXCLUSION_PX, c.edgeExclusionPx)
                .putFloat(KEY_GESTURE_VOLUME_SENSITIVITY, c.volumeSensitivity)
                .putFloat(KEY_GESTURE_BRIGHTNESS_SENSITIVITY, c.brightnessSensitivity)
                .putLong(KEY_GESTURE_LONG_PRESS_TIMEOUT_MS, c.longPressTimeoutMs)
                .putLong(KEY_GESTURE_DOUBLE_TAP_TIMEOUT_MS, c.doubleTapTimeoutMs)
                .putLong(KEY_GESTURE_SINGLE_TAP_DELAY_MS, c.singleTapDelayMs)
                .apply()
        }
    }
}
