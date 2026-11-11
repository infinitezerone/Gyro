package dev.gyro.player.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.feature.player.session.PlaybackServiceLauncher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackServiceLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PlaybackServiceLauncher {
    override fun start() {
        PlaybackService.start(context)
    }

    override fun stop() {
        PlaybackService.stop(context)
    }
}
