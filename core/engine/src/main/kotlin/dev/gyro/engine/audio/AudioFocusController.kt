package dev.gyro.engine.audio

/**
 * Platform-agnostic audio focus contract for [dev.gyro.engine.GyroPlayerEngine].
 *
 * Production Android sessions use [GyroAudioFocusManager]; pure JVM and headless environments
 * default to [NoOp], which grants playback immediately without platform dependencies.
 */
interface AudioFocusController {
    fun requestPlay(): Boolean
    fun notifyUserPaused()
    fun abandon()
    fun release()
    fun reset()

    object NoOp : AudioFocusController {
        override fun requestPlay(): Boolean = true
        override fun notifyUserPaused() {}
        override fun abandon() {}
        override fun release() {}
        override fun reset() {}
    }
}
