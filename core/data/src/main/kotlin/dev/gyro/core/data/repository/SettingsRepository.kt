package dev.gyro.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gyro_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        return UserSettings(
            autoResume = prefs.getBoolean("auto_resume", true),
            backgroundPlayMode = prefs.getString("bg_play_mode", "pip") ?: "pip",
            seekStepSeconds = prefs.getInt("seek_step_sec", 10),
            defaultHwdec = prefs.getString("default_hwdec", "mediacodec") ?: "mediacodec",
            subAutoLoad = prefs.getBoolean("sub_auto_load", true),
            gesturesEnabled = prefs.getBoolean("gestures_enabled", true)
        )
    }

    fun setAutoResume(enabled: Boolean) {
        prefs.edit().putBoolean("auto_resume", enabled).apply()
        _settings.update { it.copy(autoResume = enabled) }
    }

    fun setBackgroundPlayMode(mode: String) {
        prefs.edit().putString("bg_play_mode", mode).apply()
        _settings.update { it.copy(backgroundPlayMode = mode) }
    }

    fun setSeekStepSeconds(seconds: Int) {
        prefs.edit().putInt("seek_step_sec", seconds).apply()
        _settings.update { it.copy(seekStepSeconds = seconds) }
    }

    fun setDefaultHwdec(hwdec: String) {
        prefs.edit().putString("default_hwdec", hwdec).apply()
        _settings.update { it.copy(defaultHwdec = hwdec) }
    }

    fun setSubAutoLoad(enabled: Boolean) {
        prefs.edit().putBoolean("sub_auto_load", enabled).apply()
        _settings.update { it.copy(subAutoLoad = enabled) }
    }

    fun setGesturesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("gestures_enabled", enabled).apply()
        _settings.update { it.copy(gesturesEnabled = enabled) }
    }
}
