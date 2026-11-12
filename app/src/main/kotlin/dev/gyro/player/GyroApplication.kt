package dev.gyro.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.gyro.core.playback.PlaybackSessionController
import javax.inject.Inject

@HiltAndroidApp
class GyroApplication : Application() {
    // Eagerly create the app-scoped session controller so its collectors run for the whole process,
    // independent of any player screen, including background playback after the Activity is gone.
    @Inject
    lateinit var sessionController: PlaybackSessionController
}
