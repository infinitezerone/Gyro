package dev.gyro.core.domain.service

/**
 * Clean domain-level interface to trigger or dismiss the platform-specific
 * foreground playback notification service without creating a direct dependency
 * on Android Service internals from the domain layer.
 */
interface PlaybackServiceLauncher {
    fun start()
    fun stop()

    object NoOp : PlaybackServiceLauncher {
        override fun start() {}
        override fun stop() {}
    }
}
