package dev.gyro.player.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    val settings: StateFlow<UserSettings> = repository.settings

    fun setAutoResume(enabled: Boolean) = repository.setAutoResume(enabled)

    fun setBackgroundPlayMode(mode: String) = repository.setBackgroundPlayMode(mode)

    fun setSeekStepSeconds(seconds: Int) = repository.setSeekStepSeconds(seconds)

    fun setDefaultHwdec(hwdec: String) = repository.setDefaultHwdec(hwdec)

    fun setSubAutoLoad(enabled: Boolean) = repository.setSubAutoLoad(enabled)

    fun setGesturesEnabled(enabled: Boolean) = repository.setGesturesEnabled(enabled)
}
