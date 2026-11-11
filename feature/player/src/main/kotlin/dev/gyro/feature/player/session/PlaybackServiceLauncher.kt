package dev.gyro.feature.player.session

/**
 * Abstraction for launching and stopping the media playback background service.
 * Decouples the player feature module from the concrete Service implementation in the app module.
 */
interface PlaybackServiceLauncher {
    fun start()
    fun stop()

    object NoOp : PlaybackServiceLauncher {
        override fun start() {}
        override fun stop() {}
    }
}
