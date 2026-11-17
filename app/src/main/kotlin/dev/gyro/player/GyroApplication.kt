package dev.gyro.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.gyro.core.domain.session.PlaybackSessionObserver
import javax.inject.Inject

@HiltAndroidApp
class GyroApplication : Application() {
    // Eagerly create the app-scoped session observer so its collectors run for the whole process,
    // independent of any player screen, including background playback after the Activity is gone.
    @Inject
    lateinit var sessionObserver: PlaybackSessionObserver
}
