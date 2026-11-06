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
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface contract for managing application settings and gesture configurations.
 */
interface SettingsRepository {
    val settings: StateFlow<UserSettings>
    fun currentSettings(): UserSettings
    fun updateSettings(transform: (UserSettings) -> UserSettings): UserSettings
    fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig)

    fun setAutoResume(enabled: Boolean): UserSettings
    fun setBackgroundPlayMode(mode: String): UserSettings
    fun setSeekStepSeconds(seconds: Int): UserSettings
    fun setDefaultHwdec(hwdec: String): UserSettings
    fun setSubAutoLoad(enabled: Boolean): UserSettings
    fun setGesturesEnabled(enabled: Boolean): UserSettings

    fun setGestureConfig(config: PlayerGestureConfig)
    fun setGestureLocked(locked: Boolean)
    fun setGestureVolumeEnabled(enabled: Boolean)
    fun setVolumeGestureEnabled(enabled: Boolean)
    fun setGestureBrightnessEnabled(enabled: Boolean)
    fun setBrightnessGestureEnabled(enabled: Boolean)
    fun setGestureSeekEnabled(enabled: Boolean)
    fun setSeekGestureEnabled(enabled: Boolean)
    fun setGestureZoomEnabled(enabled: Boolean)
    fun setZoomGestureEnabled(enabled: Boolean)
    fun setGestureDoubleTapSeekEnabled(enabled: Boolean)
    fun setDoubleTapSeekEnabled(enabled: Boolean)
    fun setGestureDoubleTapPlayPauseEnabled(enabled: Boolean)
    fun setDoubleTapPlayPauseEnabled(enabled: Boolean)
    fun setGestureLongPressSpeedEnabled(enabled: Boolean)
    fun setLongPressSpeedEnabled(enabled: Boolean)
    fun setGestureSwapVolumeBrightness(swapped: Boolean)
    fun setSwapVolumeAndBrightness(swap: Boolean)
    fun setGestureDoubleTapSeekSeconds(seconds: Int): UserSettings
    fun setDoubleTapSeekSeconds(seconds: Int): UserSettings
    fun setGestureLongPressSpeed(speed: Float)
    fun setLongPressSpeed(speed: Float)
    fun setGestureVolumeSensitivity(sensitivity: Float)
    fun setVolumeSensitivity(sensitivity: Float)
    fun setGestureBrightnessSensitivity(sensitivity: Float)
    fun setBrightnessSensitivity(sensitivity: Float)
    fun setGestureEdgeExclusionPx(px: Float)
    fun setLongPressTimeoutMs(timeoutMs: Long)
    fun setDoubleTapTimeoutMs(timeoutMs: Long)
    fun setSingleTapDelayMs(delayMs: Long)

    fun resetGestureConfigToDefault()
    fun resetGestureConfigToDefaults()

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

/**
 * Factory function for tests or manual instantiation with custom DataStore.
 */
fun SettingsRepository(
    dataStore: DataStore<UserSettings>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    appScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
): SettingsRepository = DefaultSettingsRepository(dataStore, ioDispatcher, appScope, null)

/**
 * Factory function for legacy / tests with FakeSharedPreferences.
 */
fun SettingsRepository(
    prefs: SharedPreferences,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    appScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
): SettingsRepository = DefaultSettingsRepository(prefs, ioDispatcher, appScope)

/**
 * Production implementation of [SettingsRepository] backed by Jetpack DataStore.
 */
@Singleton
class DefaultSettingsRepository @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext context: Context? = null,
) : SettingsRepository {
    private var legacyPrefs: SharedPreferences? = null

    constructor(
        dataStore: DataStore<UserSettings>,
        ioDispatcher: CoroutineDispatcher,
        appScope: CoroutineScope,
    ) : this(dataStore, ioDispatcher, appScope, null)

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
    override val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val lock = Any()

    init {
        if (context != null) {
            legacyPrefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            migrateLegacyPrefsIfNeeded(context)
        }

        appScope.launch(ioDispatcher) {
            runCatching {
                dataStore.data.collectLatest { diskSettings ->
                    synchronized(lock) {
                        if (_settings.value == UserSettings()) {
                            _settings.value = diskSettings
                        }
                    }
                }
            }
        }
    }

    override fun currentSettings(): UserSettings = _settings.value

    override fun updateSettings(transform: (UserSettings) -> UserSettings): UserSettings = synchronized(lock) {
        val current = _settings.value
        val updated = transform(current)
        _settings.value = updated
        appScope.launch(ioDispatcher) {
            runCatching {
                dataStore.updateData { updated }
            }
        }
        legacyPrefs?.let { saveSettingsToPrefs(it, updated) }
        updated
    }

    override fun updateGestureConfig(transform: (PlayerGestureConfig) -> PlayerGestureConfig) {
        updateSettings { current ->
            val updatedConfig = transform(current.gestureConfig)
            current.copy(
                seekStepSeconds = updatedConfig.doubleTapSeekSeconds,
                gestureConfig = updatedConfig
            )
        }
    }

    override fun setAutoResume(enabled: Boolean) = updateSettings { it.copy(autoResume = enabled) }
    override fun setBackgroundPlayMode(mode: String) = updateSettings { it.copy(backgroundPlayMode = mode) }
    override fun setSeekStepSeconds(seconds: Int) = updateSettings { current ->
        current.copy(
            seekStepSeconds = seconds,
            gestureConfig = current.gestureConfig.copy(doubleTapSeekSeconds = seconds)
        )
    }
    override fun setDefaultHwdec(hwdec: String) = updateSettings { it.copy(defaultHwdec = hwdec) }
    override fun setSubAutoLoad(enabled: Boolean) = updateSettings { it.copy(subAutoLoad = enabled) }
    override fun setGesturesEnabled(enabled: Boolean) = updateSettings { it.copy(gesturesEnabled = enabled) }

    override fun setGestureConfig(config: PlayerGestureConfig) = updateGestureConfig { config }
    override fun setGestureLocked(locked: Boolean) = updateGestureConfig { it.copy(isLocked = locked) }
    override fun setGestureVolumeEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isVolumeEnabled = enabled) }
    override fun setVolumeGestureEnabled(enabled: Boolean) = setGestureVolumeEnabled(enabled)
    override fun setGestureBrightnessEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isBrightnessEnabled = enabled) }
    override fun setBrightnessGestureEnabled(enabled: Boolean) = setGestureBrightnessEnabled(enabled)
    override fun setGestureSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isSeekEnabled = enabled) }
    override fun setSeekGestureEnabled(enabled: Boolean) = setGestureSeekEnabled(enabled)
    override fun setGestureZoomEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isZoomEnabled = enabled) }
    override fun setZoomGestureEnabled(enabled: Boolean) = setGestureZoomEnabled(enabled)
    override fun setGestureDoubleTapSeekEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapSeekEnabled = enabled) }
    override fun setDoubleTapSeekEnabled(enabled: Boolean) = setGestureDoubleTapSeekEnabled(enabled)
    override fun setGestureDoubleTapPlayPauseEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isDoubleTapPlayPauseEnabled = enabled) }
    override fun setDoubleTapPlayPauseEnabled(enabled: Boolean) = setGestureDoubleTapPlayPauseEnabled(enabled)
    override fun setGestureLongPressSpeedEnabled(enabled: Boolean) = updateGestureConfig { it.copy(isLongPressSpeedEnabled = enabled) }
    override fun setLongPressSpeedEnabled(enabled: Boolean) = setGestureLongPressSpeedEnabled(enabled)
    override fun setGestureSwapVolumeBrightness(swapped: Boolean) = updateGestureConfig { it.copy(swapVolumeAndBrightness = swapped) }
    override fun setSwapVolumeAndBrightness(swap: Boolean) = setGestureSwapVolumeBrightness(swap)
    override fun setGestureDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    override fun setDoubleTapSeekSeconds(seconds: Int) = setSeekStepSeconds(seconds)
    override fun setGestureLongPressSpeed(speed: Float) = updateGestureConfig { it.copy(longPressSpeed = speed) }
    override fun setLongPressSpeed(speed: Float) = setGestureLongPressSpeed(speed)
    override fun setGestureVolumeSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(volumeSensitivity = sensitivity) }
    override fun setVolumeSensitivity(sensitivity: Float) = setGestureVolumeSensitivity(sensitivity)
    override fun setGestureBrightnessSensitivity(sensitivity: Float) = updateGestureConfig { it.copy(brightnessSensitivity = sensitivity) }
    override fun setBrightnessSensitivity(sensitivity: Float) = setGestureBrightnessSensitivity(sensitivity)
    override fun setGestureEdgeExclusionPx(px: Float) = updateGestureConfig { it.copy(edgeExclusionPx = px) }
    override fun setLongPressTimeoutMs(timeoutMs: Long) = updateGestureConfig { it.copy(longPressTimeoutMs = timeoutMs) }
    override fun setDoubleTapTimeoutMs(timeoutMs: Long) = updateGestureConfig { it.copy(doubleTapTimeoutMs = timeoutMs) }
    override fun setSingleTapDelayMs(delayMs: Long) = updateGestureConfig { it.copy(singleTapDelayMs = delayMs) }

    override fun resetGestureConfigToDefault() {
        updateGestureConfig { PlayerGestureConfig() }
    }

    override fun resetGestureConfigToDefaults() {
        resetGestureConfigToDefault()
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        val sp = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val hasMigratedKey = "datastore_migration_completed"
        if (sp.getBoolean(hasMigratedKey, false)) return

        val legacySettings = loadSettingsFromPrefs(sp)
        _settings.value = legacySettings

        appScope.launch(ioDispatcher) {
            dataStore.updateData { legacySettings }
            sp.edit().putBoolean(hasMigratedKey, true).apply()
        }
    }

    companion object {
        fun loadGestureConfigFromPrefs(prefs: SharedPreferences): PlayerGestureConfig {
            val doubleTapSeekSec = if (prefs.contains(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC)) {
                prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10)
            } else {
                prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10)
            }
            val swap = if (prefs.contains(SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS)) {
                prefs.getBoolean(SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, false)
            } else {
                prefs.getBoolean("gesture_swap_vol_bright", false)
            }
            val longPressSpeed = if (prefs.contains(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED)) {
                prefs.getFloat(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED, 2.0f)
            } else {
                prefs.getFloat("gesture_long_press_speed_val", 2.0f)
            }
            val volSens = if (prefs.contains(SettingsRepository.KEY_GESTURE_VOLUME_SENSITIVITY)) {
                prefs.getFloat(SettingsRepository.KEY_GESTURE_VOLUME_SENSITIVITY, 1.0f)
            } else {
                prefs.getFloat("gesture_vol_sensitivity", 1.0f)
            }

            return PlayerGestureConfig(
                isLocked = prefs.getBoolean(SettingsRepository.KEY_GESTURE_IS_LOCKED, false),
                isVolumeEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_VOLUME_ENABLED, true),
                isBrightnessEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_BRIGHTNESS_ENABLED, true),
                isSeekEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_SEEK_ENABLED, true),
                isZoomEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_ZOOM_ENABLED, true),
                isDoubleTapSeekEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_ENABLED, true),
                isDoubleTapPlayPauseEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_PLAY_PAUSE_ENABLED, true),
                isLongPressSpeedEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED_ENABLED, true),
                swapVolumeAndBrightness = swap,
                doubleTapSeekSeconds = doubleTapSeekSec,
                longPressSpeed = longPressSpeed,
                edgeExclusionPx = prefs.getFloat(SettingsRepository.KEY_GESTURE_EDGE_EXCLUSION_PX, 72f),
                volumeSensitivity = volSens,
                brightnessSensitivity = prefs.getFloat(SettingsRepository.KEY_GESTURE_BRIGHTNESS_SENSITIVITY, 1.0f),
                longPressTimeoutMs = prefs.getLong(SettingsRepository.KEY_GESTURE_LONG_PRESS_TIMEOUT_MS, 450L),
                doubleTapTimeoutMs = prefs.getLong(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_TIMEOUT_MS, 320L),
                singleTapDelayMs = prefs.getLong(SettingsRepository.KEY_GESTURE_SINGLE_TAP_DELAY_MS, 260L)
            )
        }

        fun loadSettingsFromPrefs(prefs: SharedPreferences): UserSettings {
            val gestureConfig = loadGestureConfigFromPrefs(prefs)
            val seekStep = if (prefs.contains(SettingsRepository.KEY_SEEK_STEP_SEC)) {
                prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10)
            } else {
                gestureConfig.doubleTapSeekSeconds
            }

            return UserSettings(
                autoResume = prefs.getBoolean(SettingsRepository.KEY_AUTO_RESUME, true),
                backgroundPlayMode = prefs.getString(SettingsRepository.KEY_BG_PLAY_MODE, "pip") ?: "pip",
                seekStepSeconds = seekStep,
                defaultHwdec = (prefs.getString(SettingsRepository.KEY_DEFAULT_HWDEC, HWDEC_HARDWARE) ?: HWDEC_HARDWARE)
                    .ifBlank { HWDEC_SOFTWARE },
                subAutoLoad = prefs.getBoolean(SettingsRepository.KEY_SUB_AUTO_LOAD, true),
                gesturesEnabled = prefs.getBoolean(SettingsRepository.KEY_GESTURES_ENABLED, true),
                gestureConfig = gestureConfig
            )
        }

        fun saveSettingsToPrefs(prefs: SharedPreferences, s: UserSettings) {
            val c = s.gestureConfig
            prefs.edit()
                .putBoolean(SettingsRepository.KEY_AUTO_RESUME, s.autoResume)
                .putString(SettingsRepository.KEY_BG_PLAY_MODE, s.backgroundPlayMode)
                .putInt(SettingsRepository.KEY_SEEK_STEP_SEC, s.seekStepSeconds)
                .putString(SettingsRepository.KEY_DEFAULT_HWDEC, s.defaultHwdec)
                .putBoolean(SettingsRepository.KEY_SUB_AUTO_LOAD, s.subAutoLoad)
                .putBoolean(SettingsRepository.KEY_GESTURES_ENABLED, s.gesturesEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_IS_LOCKED, c.isLocked)
                .putBoolean(SettingsRepository.KEY_GESTURE_VOLUME_ENABLED, c.isVolumeEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_BRIGHTNESS_ENABLED, c.isBrightnessEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_SEEK_ENABLED, c.isSeekEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_ZOOM_ENABLED, c.isZoomEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_ENABLED, c.isDoubleTapSeekEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_PLAY_PAUSE_ENABLED, c.isDoubleTapPlayPauseEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED_ENABLED, c.isLongPressSpeedEnabled)
                .putBoolean(SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, c.swapVolumeAndBrightness)
                .putInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, c.doubleTapSeekSeconds)
                .putFloat(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED, c.longPressSpeed)
                .putFloat(SettingsRepository.KEY_GESTURE_EDGE_EXCLUSION_PX, c.edgeExclusionPx)
                .putFloat(SettingsRepository.KEY_GESTURE_VOLUME_SENSITIVITY, c.volumeSensitivity)
                .putFloat(SettingsRepository.KEY_GESTURE_BRIGHTNESS_SENSITIVITY, c.brightnessSensitivity)
                .putLong(SettingsRepository.KEY_GESTURE_LONG_PRESS_TIMEOUT_MS, c.longPressTimeoutMs)
                .putLong(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_TIMEOUT_MS, c.doubleTapTimeoutMs)
                .putLong(SettingsRepository.KEY_GESTURE_SINGLE_TAP_DELAY_MS, c.singleTapDelayMs)
                .apply()
        }
    }
}
